#!/usr/bin/env python3
"""映射工具与 drift 检查器的规则一致性（conformance）测试。

用法：
  py -3 scripts/ci/test_permission_rules_conformance.py        # Windows
  python3 scripts/ci/test_permission_rules_conformance.py      # CI (Linux)

背景：实施计划要求 `scripts/tools/permission_annotation_map.py`（生成器）与
`scripts/ci/check_permission_annotation_drift.py`（CI 护栏）**各自独立**，
好处是生成器重构不会带崩护栏；代价是两份规则可能悄悄漂移。本测试把
「不许漂移」从注释约定变成机械断言：

  * 五张豁免清单逐条相等；
  * 两个脚本读同一个 seed 目录、扫同一批 controller 文件；
  * 两个脚本对仓库里**每个端点**解析出同一个权限码、同一个豁免判定与豁免原因；
  * 两个脚本对每个端点给出同一个**运行时候选码集合**（filter 的 anyMatch 语义，
    `hasAnyAuthority` 的依据）。

两个脚本都只在这里被 import（不执行它们的 `main()`），因此不会重新生成
`docs/superpowers/specs/2026-11-12-permission-mapping.json` 与
`...-permission-catalog-gaps.md`；本测试只做「读 + 解析」。

任一侧发生漂移时本测试**大声失败**（assertEqual 带具体差异说明）。

仅使用标准库。
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAPPING_TOOL = ROOT / "scripts" / "tools" / "permission_annotation_map.py"
DRIFT_CHECKER = ROOT / "scripts" / "ci" / "check_permission_annotation_drift.py"

# 必须逐条一致的五张豁免清单（抄自 ApiAccessControlProperties 的默认值）。
EXEMPTION_LISTS = (
    "WHITELIST",
    "EXEMPT_PATHS",
    "EXEMPT_PATH_EXCLUSIONS",
    "INTERNAL_SIGNATURE_PATHS",
    "SIGNATURE_OR_PERMISSION_PATHS",
)

# 映射工具的输出（生成器会写它们；import 绝不能触发写入）。
GENERATED_ARTIFACTS = (
    ROOT / "docs/superpowers/specs/2026-11-12-permission-mapping.json",
    ROOT / "docs/superpowers/specs/2026-11-12-permission-catalog-gaps.md",
)


def load_module(name: str, path: Path):
    """按路径 import 一个模块（两个脚本都不在 sys.path 上）。

    `module_from_spec` 给的 `__name__` 不是 "__main__"，因此模块里的
    `if __name__ == "__main__": sys.exit(main())` 守卫不会触发 —— 生成器的
    `main()` 会写文件，import 时绝不能执行。
    """
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:  # pragma: no cover - 路径写错才会发生
        raise ImportError(f"无法加载模块：{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def quiet(function, *args, **kwargs):
    """调用会打印提示的函数并把输出吞掉（对比时只看结果，不看噪音）。"""
    buffer_out = io.StringIO()
    buffer_err = io.StringIO()
    with contextlib.redirect_stdout(buffer_out), contextlib.redirect_stderr(buffer_err):
        return function(*args, **kwargs)


def capture(function, *args, **kwargs):
    """调用会打印提示的函数，返回（结果, stdout+stderr）。"""
    buffer_out = io.StringIO()
    buffer_err = io.StringIO()
    with contextlib.redirect_stdout(buffer_out), contextlib.redirect_stderr(buffer_err):
        result = function(*args, **kwargs)
    return result, buffer_out.getvalue() + buffer_err.getvalue()


def strip_shape(index: dict) -> dict:
    """去掉生成器独有的 `shape` 元数据，只比业务字段。"""
    return {
        key: {field: value for field, value in row.items() if field != "shape"}
        for key, row in index.items()
    }


# 一个 javadoc 长到「方法声明查找窗口」之外（> METHOD_LOOKUP_WINDOW 个字符）的 controller：
# 两侧必须给出同样的结论（同样跳过该端点、同样告警）。
LONG_JAVADOC_CONTROLLER = """package demo;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LongDocController {

    @GetMapping("/things")
    /**
""" + "     * " + ("很长的说明 " * 2000) + """
     */
    public String things() {
        return "ok";
    }

}
"""


class PermissionRulesConformanceTest(unittest.TestCase):
    """两个脚本必须对同一份输入给出同一份结论。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.mapping = load_module("perm_mapping_tool_under_test", MAPPING_TOOL)
        cls.checker = load_module("perm_drift_checker_under_test", DRIFT_CHECKER)

    # --- 结构性前提 ------------------------------------------------------
    def test_both_scripts_have_a_main_guard(self) -> None:
        """两个脚本都必须有 `__main__` 守卫，否则 import 会执行 main()。"""
        for path in (MAPPING_TOOL, DRIFT_CHECKER):
            with self.subTest(script=path.name):
                source = path.read_text(encoding="utf-8")
                self.assertIn(
                    'if __name__ == "__main__":',
                    source,
                    f"{path.name} 缺少 __main__ 守卫，import 时会执行 main()",
                )

    def test_import_does_not_regenerate_the_mapping_artifacts(self) -> None:
        """import 不得触发生成器写文件（否则 CI 跑测试会污染工作树）。"""
        before = [
            (path.stat().st_mtime_ns, path.stat().st_size) if path.exists() else None
            for path in GENERATED_ARTIFACTS
        ]
        load_module("perm_mapping_tool_import_probe", MAPPING_TOOL)
        after = [
            (path.stat().st_mtime_ns, path.stat().st_size) if path.exists() else None
            for path in GENERATED_ARTIFACTS
        ]
        self.assertEqual(before, after, "import 映射工具改写了生成产物")

    # --- 规则一致性 ------------------------------------------------------
    def test_exemption_constant_lists_are_identical(self) -> None:
        """五张豁免清单必须逐条相等（顺序也一致）。"""
        for name in EXEMPTION_LISTS:
            with self.subTest(constant=name):
                self.assertTrue(
                    hasattr(self.mapping, name), f"映射工具缺少常量 {name}"
                )
                self.assertTrue(
                    hasattr(self.checker, name), f"检查器缺少常量 {name}"
                )
                self.assertEqual(
                    list(self.mapping.__dict__[name]),
                    list(self.checker.__dict__[name]),
                    f"豁免清单 {name} 在两个脚本里不一致",
                )

    def test_seed_directory_agrees(self) -> None:
        """两个脚本的默认 seed 目录必须指向同一个地方。"""
        self.assertEqual(
            Path(self.mapping.SEED_DIR).resolve(),
            Path(self.checker.SEED_DIR).resolve(),
            "两个脚本的默认 seed 目录不一致",
        )

    def test_method_lookup_window_is_identical(self) -> None:
        """方法声明查找窗口必须一致。

        窗口不同 → 同一份源码在两个脚本里会解析出不同数量的端点（一个跳过、一个报错），
        因此这里直接比对常量本身，而不只是比对最终结论。
        """
        self.assertTrue(
            hasattr(self.mapping, "METHOD_LOOKUP_WINDOW"), "映射工具缺少 METHOD_LOOKUP_WINDOW"
        )
        self.assertTrue(
            hasattr(self.checker, "METHOD_LOOKUP_WINDOW"), "检查器缺少 METHOD_LOOKUP_WINDOW"
        )
        self.assertEqual(
            self.mapping.METHOD_LOOKUP_WINDOW,
            self.checker.METHOD_LOOKUP_WINDOW,
            "两个脚本的 METHOD_LOOKUP_WINDOW 不一致",
        )

    def test_catalog_index_is_identical(self) -> None:
        """同一份 seed 解析出的 (url, method) → 权限码必须完全一致。"""
        map_rows, _map_notes = quiet(self.mapping.parse_catalog)
        map_index = quiet(self.mapping.build_catalog_index, map_rows)
        checker_index, _codes, _notes, checker_rows, _skipped = quiet(
            self.checker.parse_catalog, self.checker.SEED_DIR
        )
        self.assertEqual(
            len(map_rows), checker_rows, "两个脚本解析出的目录行数不一致"
        )
        self.assertEqual(
            len(map_index),
            len(checker_index),
            "两个脚本解析出的 (url, method) 对数不一致",
        )
        self.assertEqual(
            strip_shape(map_index),
            strip_shape(checker_index),
            "两个脚本解析出的权限目录索引不一致",
        )

    def test_controller_file_sets_are_identical(self) -> None:
        """两个脚本必须扫到同一批 controller 文件。"""
        map_files = quiet(self.mapping.controller_files)
        checker_files = quiet(self.checker.controller_files, self.checker.ROOT, self.checker.ROOT)
        self.assertEqual(
            [path.resolve() for path in map_files],
            [path.resolve() for path in checker_files],
            "两个脚本扫描到的 controller 文件集合不一致",
        )

    def test_comment_stripping_is_identical(self) -> None:
        """注释置空算法必须逐字一致。

        生成器曾经「删除」注释、护栏「置空」注释：源码长度不同 → 方法声明查找窗口
        量到的距离不同 → 同一份源码在两侧得到不同的端点集合。这里直接比对置空结果。
        """
        compared = 0
        for path in quiet(self.mapping.controller_files):
            source = path.read_text(encoding="utf-8", errors="replace")
            map_stripped = self.mapping.strip_java_comments(source)
            checker_stripped = self.checker.strip_java_comments(source)
            self.assertEqual(
                map_stripped,
                checker_stripped,
                f"{path.name}：两个脚本的注释置空结果不同",
            )
            # 置空必须保留行数（诊断行号依赖它）。
            self.assertEqual(
                map_stripped.count("\n"), source.count("\n"), f"{path.name}：行数被改动"
            )
            compared += 1
        self.assertGreater(compared, 0, "没有可比对的 controller 文件，测试等于没跑")

    def test_long_javadoc_case_is_identical(self) -> None:
        """长 javadoc 的 controller：两个脚本必须给出同样的结论。

        窗口按字符数量计算，注释置空算法不同时两侧会一个保留端点、一个跳过端点
        （已提交产物与 CI 护栏就会对同一份源码给出不同结论）。
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            controller = root / "LongDocController.java"
            controller.write_text(LONG_JAVADOC_CONTROLLER, encoding="utf-8")
            original_root = self.mapping.ROOT
            self.mapping.ROOT = root
            try:
                (_rel, map_endpoints), map_output = capture(
                    self.mapping.parse_controller, controller
                )
                checker_endpoints, _annotations = quiet(
                    self.checker.parse_controller, controller, root
                )
                checker_warnings = self.checker.pending_warnings()
            finally:
                self.mapping.ROOT = original_root

            self.assertEqual(
                len(map_endpoints),
                len(checker_endpoints),
                "长 javadoc 场景下两个脚本解析出的端点数不一致",
            )
            self.assertEqual(
                {(e["httpMethod"], e["path"]) for e in map_endpoints},
                {(e["httpMethod"], e["path"]) for e in checker_endpoints},
                "长 javadoc 场景下两个脚本解析出的端点不一致",
            )
            # 两边都要「跳过该端点 + 告警」，而不是一个跳过、一个中断。
            self.assertIn("跳过该端点", map_output)
            self.assertTrue(
                any("跳过该端点" in message for message in checker_warnings),
                f"检查器没有记录跳过告警：{checker_warnings}",
            )

    def test_endpoint_codes_and_exemptions_are_identical(self) -> None:
        """每个端点：目录绑定、豁免判定、豁免原因都必须一致。"""
        map_rows, _map_notes = quiet(self.mapping.parse_catalog)
        map_index = quiet(self.mapping.build_catalog_index, map_rows)
        checker_index, _codes, _notes, _rows, _skipped = quiet(
            self.checker.parse_catalog, self.checker.SEED_DIR
        )

        compared = 0
        for path in quiet(self.mapping.controller_files):
            _rel, map_endpoints = quiet(self.mapping.parse_controller, path)
            checker_endpoints, _annotations = quiet(
                self.checker.parse_controller, path, self.checker.ROOT
            )
            map_pairs = {
                (endpoint["httpMethod"], endpoint["path"])
                for endpoint in map_endpoints
            }
            checker_pairs = {
                (endpoint["httpMethod"], endpoint["path"])
                for endpoint in checker_endpoints
            }
            self.assertEqual(
                map_pairs,
                checker_pairs,
                f"{path.name}：两个脚本解析出的端点集合不一致",
            )
            for http_method, endpoint_path in sorted(map_pairs):
                with self.subTest(file=path.name, method=http_method, path=endpoint_path):
                    map_row = quiet(
                        self.mapping.catalog_lookup, map_index, endpoint_path, http_method
                    )
                    checker_row = quiet(
                        self.checker.catalog_lookup,
                        checker_index,
                        endpoint_path,
                        http_method,
                    )
                    self.assertEqual(
                        map_row["code"] if map_row else None,
                        checker_row["code"] if checker_row else None,
                        f"{http_method} {endpoint_path}：目录绑定不一致",
                    )
                    map_reason = self.mapping.exemption(endpoint_path)
                    checker_reason = self.checker.exemption(endpoint_path)
                    self.assertEqual(
                        map_reason,
                        checker_reason,
                        f"{http_method} {endpoint_path}：豁免判定/原因不一致",
                    )
                    self.assertEqual(
                        map_reason is not None,
                        checker_reason is not None,
                        f"{http_method} {endpoint_path}：豁免标记不一致",
                    )
                compared += 1
        self.assertGreater(compared, 0, "没有可比对的端点，测试等于没跑")


    def test_runtime_candidate_sets_are_identical(self) -> None:
        """每个端点的运行时候选码集合必须一致（`hasAnyAuthority` 的依据）。

        filter 是 anyMatch：字面量行与模板行可能同时命中同一个请求 URL。两侧各有一份
        `runtime_candidates` 实现（有意不互相 import），漂移会让「注解覆盖全部候选」
        这条规则在生成器与护栏之间失效。
        """
        map_rows, _map_notes = quiet(self.mapping.parse_catalog)
        map_index = quiet(self.mapping.build_catalog_index, map_rows)
        checker_index, _codes, _notes, _rows, _skipped = quiet(
            self.checker.parse_catalog, self.checker.SEED_DIR
        )

        compared = 0
        multi = 0
        for path in quiet(self.mapping.controller_files):
            _rel, map_endpoints = quiet(self.mapping.parse_controller, path)
            for endpoint in map_endpoints:
                http_method = endpoint["httpMethod"]
                endpoint_path = endpoint["path"]
                map_candidates = quiet(
                    self.mapping.runtime_candidates, map_index, endpoint_path, http_method
                )
                checker_candidates = quiet(
                    self.checker.runtime_candidates, checker_index, endpoint_path, http_method
                )
                self.assertEqual(
                    map_candidates,
                    checker_candidates,
                    f"{path.name} {http_method} {endpoint_path}：运行时候选码集合不一致",
                )
                if len(map_candidates) > 1:
                    multi += 1
                compared += 1
        self.assertGreater(compared, 0, "没有可比对的端点，测试等于没跑")
        # 仓库里必须真的存在多候选端点，否则上面这段比较对 C1 是空转。
        self.assertGreater(
            multi, 0, "仓库里没有多候选端点：runtime_candidates 的用例退化成了空跑"
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
