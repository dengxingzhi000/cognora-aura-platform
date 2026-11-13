#!/usr/bin/env python3
"""`check_permission_annotation_drift.py` 的 fixture 测试（标准库 unittest）。

用法：
  py -3 scripts/ci/test_check_permission_annotation_drift.py        # Windows
  python3 scripts/ci/test_check_permission_annotation_drift.py      # CI (Linux)

每个用例在临时目录里搭一个最小仓库：
  * `<tmp>/catalog/*.sql`      —— seed 权限目录（迷你版）；
  * `<tmp>/controllers/*.java` —— 待检查的 controller。

然后以子进程运行检查器（`--root/--catalog/--controllers` 全部指向临时目录），
断言退出码与诊断输出。两个方向都钉住：
  * 错误注解 → 退出码 1，输出含违规权限码与源文件名；
  * 正确注解 → 退出码 0。

仅使用标准库。
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parent / "check_permission_annotation_drift.py"

# 简报指定的迷你目录行：('code','name','menu','url','METHOD',sort,risk)
CREATE_ROW = "('demo:thing:create','演示','menu:demo','/api/v1/things','POST',1,3)"
LIST_ROW = "('demo:thing:list','演示','menu:demo','/api/v1/things/list','GET',1,1)"
AUDIT_ROW = "('demo:thing:audit','演示','menu:demo','/api/v1/audit','GET',1,1)"

SEED_HEADER = (
    "INSERT INTO sys_permission (\n"
    "    id, parent_id, permission_code, permission_name, permission_type,\n"
    "    api_path, http_method, sort_order, visible, status\n"
    ")\n"
    "VALUES\n"
)

CONTROLLER_HEAD = """package demo;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DemoController {

"""


def controller(*methods: str) -> str:
    return CONTROLLER_HEAD + "\n".join(methods) + "\n}\n"


def method(mapping: str, spel: str, name: str) -> str:
    return (
        f'    @{mapping}\n'
        f'    @PreAuthorize("{spel}")\n'
        f'    public String {name}() {{\n'
        f'        return "ok";\n'
        f'    }}\n'
    )


class DriftCheckerFixtureTest(unittest.TestCase):
    """每个用例搭一套 fixture 并跑一次检查器。"""

    def run_checker(
        self,
        rows: list[str],
        java: str,
        *,
        extra_catalog_files: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            catalog = root / "catalog"
            controllers = root / "controllers"
            catalog.mkdir()
            controllers.mkdir()
            (catalog / "019_fixture.sql").write_text(
                SEED_HEADER + "    " + ",\n    ".join(rows) + ";\n", encoding="utf-8"
            )
            for name, content in (extra_catalog_files or {}).items():
                (catalog / name).write_text(content, encoding="utf-8")
            (controllers / "DemoController.java").write_text(java, encoding="utf-8")
            env = dict(os.environ)
            env["PYTHONIOENCODING"] = "utf-8"
            return subprocess.run(
                [
                    sys.executable,
                    str(CHECKER),
                    "--root",
                    str(root),
                    "--catalog",
                    str(catalog),
                    "--controllers",
                    str(controllers),
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env=env,
            )

    @staticmethod
    def output(result: subprocess.CompletedProcess[str]) -> str:
        return result.stdout + result.stderr

    # --- 错误方向：注解必须与目录一致 -------------------------------------
    def test_unknown_code_is_an_error(self) -> None:
        """目录里没有的权限码 → 退出码 1，输出含违规码与文件名。"""
        result = self.run_checker(
            [CREATE_ROW],
            controller(
                method('PostMapping("/things")', "hasAuthority('demo:thing:create')", "create"),
                method('PostMapping("/things/wrong")', "hasAuthority('demo:thing:wrong')", "wrong"),
            ),
        )
        output = self.output(result)
        self.assertEqual(result.returncode, 1, output)
        self.assertIn("demo:thing:wrong", output)
        self.assertIn("DemoController.java", output)

    def test_code_bound_to_another_code_is_an_error(self) -> None:
        """同一个 (path, method) 在目录里绑了别的码 → 退出码 1，输出含期望码。"""
        result = self.run_checker(
            [CREATE_ROW, LIST_ROW],
            controller(method('PostMapping("/things")', "hasAuthority('demo:thing:list')", "create")),
        )
        output = self.output(result)
        self.assertEqual(result.returncode, 1, output)
        self.assertIn("demo:thing:list", output)
        self.assertIn("expected demo:thing:create", output)

    def test_annotation_on_exempt_endpoint_is_an_error(self) -> None:
        """豁免端点上的权限注解 → 退出码 1（防止注解被打回豁免端点）。"""
        java = controller(
            method('PostMapping("/things")', "hasAuthority('demo:thing:create')", "create")
        ).replace('@RequestMapping("/api/v1")', '@RequestMapping("/api/auth")')
        java = java.replace('PostMapping("/things")', 'PostMapping("/logout")')
        result = self.run_checker([CREATE_ROW], java)
        output = self.output(result)
        self.assertEqual(result.returncode, 1, output)
        self.assertIn("exempt", output.lower())
        self.assertIn("DemoController.java", output)

    # --- 通过方向：注解与目录一致 -----------------------------------------
    def test_correct_code_passes(self) -> None:
        """注解与目录一致 → 退出码 0，无 ERROR。"""
        result = self.run_checker(
            [CREATE_ROW],
            controller(method('PostMapping("/things")', "hasAuthority('demo:thing:create')", "create")),
        )
        output = self.output(result)
        self.assertEqual(result.returncode, 0, output)
        self.assertNotIn("ERROR", output)
        self.assertIn("errors=0", output)

    def test_is_authenticated_is_ignored(self) -> None:
        """`isAuthenticated()` 一律忽略：不检查、不报错。"""
        result = self.run_checker(
            [CREATE_ROW],
            controller(
                method('PostMapping("/things")', "hasAuthority('demo:thing:create')", "create"),
                method('PostMapping("/things/me")', "isAuthenticated()", "me"),
            ),
        )
        output = self.output(result)
        self.assertEqual(result.returncode, 0, output)
        self.assertNotIn("ERROR", output)

    def test_unbound_code_is_only_a_warning(self) -> None:
        """码在目录里存在、但该 (path, method) 没绑定 → 只报 WARNING，不失败。"""
        result = self.run_checker(
            [AUDIT_ROW],
            controller(method('PostMapping("/things")', "hasAuthority('demo:thing:audit')", "create")),
        )
        output = self.output(result)
        self.assertEqual(result.returncode, 0, output)
        self.assertIn("WARNING", output)
        self.assertIn("demo:thing:audit", output)
        self.assertNotIn("ERROR", output)

    def test_unknown_spel_is_reported_but_not_fatal(self) -> None:
        """无法判定的 SpEL 形态只报告（CONCERN），不猜语义、不失败。"""
        result = self.run_checker(
            [CREATE_ROW],
            controller(
                method('PostMapping("/things")', "hasAuthority('demo:thing:create')", "create"),
                method('PostMapping("/things/other")', "hasRole('ADMIN')", "other"),
            ),
        )
        output = self.output(result)
        self.assertEqual(result.returncode, 0, output)
        self.assertNotIn("ERROR", output)
        self.assertIn("hasRole('ADMIN')", output)

    def test_duplicate_catalog_row_with_same_code_is_a_note(self) -> None:
        """同码重复登记（目录里的已知重复）→ 只记 NOTE，不失败。"""
        result = self.run_checker(
            [CREATE_ROW],
            controller(method('PostMapping("/things")', "hasAuthority('demo:thing:create')", "create")),
            extra_catalog_files={
                "023_duplicate.sql": SEED_HEADER + "    " + CREATE_ROW + ";\n",
            },
        )
        output = self.output(result)
        self.assertEqual(result.returncode, 0, output)
        self.assertNotIn("ERROR", output)

    def test_conflicting_catalog_rows_abort(self) -> None:
        """同一 (url, method) 绑到不同权限码 → 解析失败、退出码 1。"""
        result = self.run_checker(
            [CREATE_ROW],
            controller(method('PostMapping("/things")', "hasAuthority('demo:thing:create')", "create")),
            extra_catalog_files={
                "023_conflict.sql": (
                    SEED_HEADER
                    + "    ('demo:thing:other','演示','menu:demo','/api/v1/things','POST',2,1);\n"
                ),
            },
        )
        output = self.output(result)
        self.assertEqual(result.returncode, 1, output)
        self.assertNotIn("Traceback", output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
