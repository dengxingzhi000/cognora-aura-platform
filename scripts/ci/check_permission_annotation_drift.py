#!/usr/bin/env python3
"""@PreAuthorize ↔ 权限目录 drift 检查器（CI 护栏）。

用法：
  py -3 scripts/ci/check_permission_annotation_drift.py          # Windows
  python3 scripts/ci/check_permission_annotation_drift.py        # CI (Linux)
  py -3 scripts/ci/check_permission_annotation_drift.py \
      --catalog <seed 目录> --controllers <controller 目录> [--root <仓库根>]

退出码：0 = 没有 error（允许 warning）；1 = 有 error，或目录/源码解析失败。

检查规则（与 `scripts/tools/permission_annotation_map.py` 使用同一套目录解析、
controller 解析与豁免规则；两个脚本有意各自独立、**不互相 import**，改动规则时
必须同步两边，常量也必须同步）：
  * 只检查 `@PreAuthorize("hasAuthority('<code>')")`。
    - `isAuthenticated()` 与其它 SpEL 形态一律**完全忽略**：不检查、不报告、不计数
      （平台既有 20 处 `isAuthenticated()`，其中多条落在豁免端点上，它们是合法的；
      其它 SpEL 形态不猜语义，也不当成违规）。
    - `hasAuthority(...)` 但绑定不到任何 request mapping（类级注解、写法异常）→
      CONCERN：无法判定它保护哪个端点，交人工确认（本仓库当前为 0 条）。
  * 权限码在目录里完全不存在 → ERROR。
  * 端点 (path, httpMethod) 在目录里有绑定、但权限码不同 → ERROR（打印 expected）。
  * 权限码存在、但该 (path, httpMethod) 没有目录绑定 → WARNING（目录缺口，只报告，
    不失败，也不在本脚本里补目录数据）。
  * 端点命中豁免清单 → ERROR（防止后续把权限注解打回豁免端点）。

诊断格式：`file:line code expected ...`（error / warning / concern 各一段，
按 file:line 排序，输出确定）。

仅使用标准库。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# seed 权限目录（CLI 默认值；与映射工具的同名常量指向同一个目录，
# 一致性由 scripts/ci/test_permission_rules_conformance.py 钉住）。
SEED_DIR = ROOT / "scripts/db/seed"

DOMAINS = ("platform", "scm", "ecom", "lms")
# 这些目录片段下的 controller 不属于本仓库的运行时端点（工作副本/嵌套仓库/构建产物）。
EXCLUDED_FRAGMENTS = {
    ".vmcheck",
    "enterprise-agent",
    "cognora-aura-web",
    "target",
    "node_modules",
    ".git",
    ".superpowers",
}

# ---------------------------------------------------------------------------
# 豁免清单：逐条抄自
# platform/common/web/src/main/java/com/cognora/platform/common/security/config/ApiAccessControlProperties.java
# （2026-11-13 的当前默认值）。语义见 ApiAccessControlFilter#isWhitelisted/#isExempt
# 与 RequestPathMatcher：路径先规范化（去矩阵参数、百分号解码、去尾斜杠），再按
# Ant 风格匹配（`**` 任意段、`*` 单段、`?` 单字符）。改默认值时必须同步改这里，
# 也必须同步改 scripts/tools/permission_annotation_map.py 的同名常量。
# ---------------------------------------------------------------------------
WHITELIST = [
    "/api/auth/login",
    "/api/auth/register",
    "/api/auth/refresh",
    "/actuator/health",
]

EXEMPT_PATHS = [
    "/api/auth/logout",
    "/oauth2/logout",
    "/api/auth/userinfo",
    "/api/auth/sessions",
    "/api/auth/sessions/**",
    "/api/system/users/me",
    "/api/system/users/change-password",
    "/api/auth/2fa/**",
    "/api/auth/webauthn/credentials/health",
    "/api/auth/login",
    "/api/auth/register",
    "/api/auth/refresh",
    "/sys-audit-log",
    "/api/v1/inventory/reservation/**",
    "/sys-notification-audit/**",
    "/api/v1/payments",
    "/api/v1/refunds",
    "/api/v1/payments/callback/**",
    "/actuator/**",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/api/public/**",
    "/error",
    "/api/v1/mall/cart",
    "/api/v1/mall/cart/**",
    "/api/v1/mall/products/**/reviews",
    "/api/v1/mall/reviews",
    "/api/v1/mall/reviews/**",
    "/api/v1/members/**",
    "/api/v1/promotions/**",
    "/sys-user-notification",
    "/sys-user-notification/**",
    "/sys-user-notification-preference",
    "/sys-user-notification-preference/**",
    "/api/v1/search/products",
    "/api/v1/search/products/**",
    "/api/v1/payments/create",
    "/api/v1/payments/*",
    "/api/v1/payments/*/cancel",
    "/api/v1/payments/refunds",
    "/api/v1/payments/refunds/*",
]

EXEMPT_PATH_EXCLUSIONS = [
    "/api/v1/payments/page",
    "/api/v1/payments/refunds/page",
]

INTERNAL_SIGNATURE_PATHS = [
    "/api/v1/inventory/reservation/**",
    "/sys-notification-audit/**",
    "/api/v1/payments",
    "/api/v1/refunds",
    "/api/system/users/*/update-login",
    "/api/auth/admin/users/**",
    "/api/system/permissions/find-by-url",
    "/api/system/permissions/user/**",
    "/api/v1/pur-order/in-transit",
    "/api/v1/orders/*/pay",
]

SIGNATURE_OR_PERMISSION_PATHS = [
    "/api/v1/search/index/**",
]

EXEMPT_REASON_WHITELIST = "whitelist"
EXEMPT_REASON_EXEMPT_PATHS = "exemptPaths"
EXEMPT_REASON_INTERNAL_SIGNATURE = "internalSignaturePaths"
EXEMPT_REASON_SIGNATURE_OR_PERMISSION = "signatureOrPermissionPaths"

_METHOD_TOKEN = r"(GET|POST|PUT|DELETE|PATCH|\*)"

# ---------------------------------------------------------------------------
# SQL 目录行解析（seed 里实际存在的四种历史写法，与映射工具一致）：
#   ('code','name','menu','url','METHOD',sort,risk)          —— 019/020/023-026/033
#   ('code','name','menu','url','METHOD',sort,risk,visible)  —— 002
#   ('code','name','url','METHOD',sort,risk)                 —— 022/029/030/031/032
#   ('code','name',type,'url','METHOD',sort,...)             —— 021（type 为数字 3）
# 只认 URL + 方法配对的行；002 的角色/菜单行、005 的决策矩阵行没有方法字段，不会被匹配。
# ---------------------------------------------------------------------------
_CATALOG_PATTERNS = (
    re.compile(
        r"\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'"
        + _METHOD_TOKEN
        + r"'\s*,\s*-?\d+\s*,\s*-?\d+\s*,\s*(?:TRUE|FALSE)\s*\)",
        re.IGNORECASE,
    ),
    re.compile(
        r"\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'"
        + _METHOD_TOKEN
        + r"'\s*,\s*-?\d+\s*,\s*-?\d+\s*\)",
        re.IGNORECASE,
    ),
    re.compile(
        r"\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'"
        + _METHOD_TOKEN
        + r"'\s*,\s*-?\d+\s*,\s*-?\d+\s*\)",
        re.IGNORECASE,
    ),
    # 021：('code','name',<type>,'url','METHOD',...)
    re.compile(
        r"'([^']*)'\s*,\s*'([^']*)'\s*,\s*\d+\s*,\s*'([^']*)'\s*,\s*'"
        + _METHOD_TOKEN
        + r"'\s*,",
        re.IGNORECASE,
    ),
)
# 每个 pattern 的 (权限码下标, URL 下标, HTTP 方法下标)
_CATALOG_GROUPS = ((1, 4, 5), (1, 4, 5), (1, 3, 4), (1, 3, 4))

# `INSERT INTO sys_permission ... ;` 语句块里「元组第一个字面量」= 权限码。
# 目录行以外的权限码（api_path 为 NULL 的「只有码、没有绑定」行）也属于目录，
# 但没有 (url, method) 绑定：注解用这类码时只能报 WARNING，不能报 ERROR。
_PERMISSION_INSERT = re.compile(r"INSERT\s+INTO\s+sys_permission\b", re.IGNORECASE)
_PERMISSION_ROW_CODE = re.compile(r"\(\s*'((?:[^']|'')*)'\s*,")

JAVA_METHOD_DECL = re.compile(
    r"(?:public|protected|private)\s+[^;{}=()]*?\b([A-Za-z_]\w*)\s*\("
)
MAPPING_ANNOTATION = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b")
CLASS_REQUEST_MAPPING = re.compile(r"@RequestMapping\b")
PRE_AUTHORIZE = re.compile(r"@PreAuthorize\b")
HAS_AUTHORITY = re.compile(r"^hasAuthority\(\s*'([^']*)'\s*\)$")
# `isAuthenticated()` 与其它 SpEL 形态一律忽略：这个常量只用于说明「已识别的第二种形态」，
# 不参与判定（见 evaluate()）。
IS_AUTHENTICATED = re.compile(r"^isAuthenticated\(\s*\)$")

# mapping 注解之后向前找方法声明的窗口（字符数）。javadoc / 注解块很长时容易超出，
# 窗口不足会误判「找不到方法声明」，因此放宽；仍然找不到就跳过该端点而不是中断整轮检查。
_METHOD_LOOKUP_WINDOW = 8000

SEVERITY_ERROR = "error"
SEVERITY_WARNING = "warning"
SEVERITY_CONCERN = "concern"


class ParseError(RuntimeError):
    """源码/目录结构与预期不符：宁可失败，也不产出错误的检查结论。"""


def warn(message: str) -> None:
    """告警行（不改变退出码）。"""
    print(f"WARNING {message}")


# ---------------------------------------------------------------------------
# 路径工具（与映射工具同一套语义）
# ---------------------------------------------------------------------------
def normalize_path(path: str) -> str:
    """规范化路径：单斜杠、带前导斜杠、去尾斜杠。"""
    if not path:
        return "/"
    normalized = re.sub(r"/+", "/", "/" + path.strip())
    if len(normalized) > 1:
        normalized = normalized.rstrip("/")
    return normalized


def join_paths(base: str, sub: str) -> str:
    """拼接类级 @RequestMapping 与方法级 mapping 的路径。"""
    if not sub:
        return normalize_path(base)
    if not base:
        return normalize_path(sub)
    return normalize_path(base.rstrip("/") + "/" + sub.lstrip("/"))


def split_segments(path: str) -> list[str]:
    return [segment for segment in normalize_path(path).split("/") if segment]


def is_placeholder(segment: str) -> bool:
    """`{id}` / `{orderNo}` 这类路径占位符段。"""
    return len(segment) > 2 and segment.startswith("{") and segment.endswith("}")


def segment_matches(pattern: str, value: str) -> bool:
    """段内 Ant 匹配：`*` 任意字符、`?` 单字符；其余按字面量。"""
    if pattern == value:
        return True
    if "*" not in pattern and "?" not in pattern:
        return False
    regex = "".join(
        ".*" if char == "*" else "." if char == "?" else re.escape(char)
        for char in pattern
    )
    return re.fullmatch(regex, value) is not None


def ant_matches(pattern: str, path: str) -> bool:
    """按 Ant 语义匹配路径（`**` 任意段、`*` 单段、`?` 单字符）。"""
    pattern_segments = split_segments(pattern)
    path_segments = split_segments(path)
    memo: dict[tuple[int, int], bool] = {}

    def walk(pi: int, si: int) -> bool:
        key = (pi, si)
        if key in memo:
            return memo[key]
        if pi == len(pattern_segments):
            result = si == len(path_segments)
        elif pattern_segments[pi] == "**":
            result = walk(pi + 1, si) or (si < len(path_segments) and walk(pi, si + 1))
        elif si == len(path_segments):
            result = False
        else:
            result = segment_matches(pattern_segments[pi], path_segments[si]) and walk(
                pi + 1, si + 1
            )
        memo[key] = result
        return result

    return walk(0, 0)


def matches_any(path: str, patterns: list[str]) -> bool:
    return any(ant_matches(pattern, path) for pattern in patterns if pattern)


def _placeholder_probe_path(path: str) -> str:
    """把 `{var}` 换成普通段，让豁免清单的 `*` / `**` 能匹配到占位符段。"""
    segments = [
        "_var_" if is_placeholder(segment) else segment for segment in split_segments(path)
    ]
    return "/" + "/".join(segments)


def exemption(path: str) -> str | None:
    """返回豁免原因；不在任何豁免清单里返回 None（与 ApiAccessControlFilter 顺序一致）。

    `exemptPathExclusions` 只作用于 `exemptPaths` 分支：命中排除项时仍要继续检查
    `internalSignaturePaths` / `signatureOrPermissionPaths`（排除项不是「整个豁免短路」）。
    """
    probe = _placeholder_probe_path(path)
    if matches_any(probe, WHITELIST):
        return EXEMPT_REASON_WHITELIST
    if not matches_any(probe, EXEMPT_PATH_EXCLUSIONS) and matches_any(probe, EXEMPT_PATHS):
        return EXEMPT_REASON_EXEMPT_PATHS
    if matches_any(probe, INTERNAL_SIGNATURE_PATHS):
        return EXEMPT_REASON_INTERNAL_SIGNATURE
    if matches_any(probe, SIGNATURE_OR_PERMISSION_PATHS):
        return EXEMPT_REASON_SIGNATURE_OR_PERMISSION
    return None


def _self_check_exempt_lists() -> None:
    """抄写自检：清单项必须是绝对路径，且无明显重复。"""
    for name, patterns in (
        ("WHITELIST", WHITELIST),
        ("EXEMPT_PATHS", EXEMPT_PATHS),
        ("EXEMPT_PATH_EXCLUSIONS", EXEMPT_PATH_EXCLUSIONS),
        ("INTERNAL_SIGNATURE_PATHS", INTERNAL_SIGNATURE_PATHS),
        ("SIGNATURE_OR_PERMISSION_PATHS", SIGNATURE_OR_PERMISSION_PATHS),
    ):
        if not patterns:
            raise ParseError(f"{name} 为空，抄写 ApiAccessControlProperties 时漏了内容")
        for pattern in patterns:
            if not pattern.startswith("/"):
                raise ParseError(f"{name} 含非绝对路径项：{pattern}")
        if len(set(patterns)) != len(patterns):
            raise ParseError(f"{name} 含重复项")


# ---------------------------------------------------------------------------
# Java 源码解析
# ---------------------------------------------------------------------------
def strip_java_comments(source: str) -> str:
    """把注释替换成等长空格（保留换行），行号与源文件保持一致。

    字符串/字符字面量的内容原样保留：注解参数里就有字符串。
    """
    out = list(source)
    length = len(source)
    i = 0
    while i < length:
        char = source[i]
        nxt = source[i + 1] if i + 1 < length else ""
        if char == "/" and nxt == "/":
            while i < length and source[i] != "\n":
                out[i] = " "
                i += 1
        elif char == "/" and nxt == "*":
            out[i] = " "
            out[i + 1] = " "
            i += 2
            while i + 1 < length and not (source[i] == "*" and source[i + 1] == "/"):
                if source[i] != "\n":
                    out[i] = " "
                i += 1
            if i < length:
                out[i] = " "
            if i + 1 < length:
                out[i + 1] = " "
            i += 2
        elif char in ("\"", "'"):
            quote = char
            i += 1
            while i < length:
                if source[i] == "\\" and i + 1 < length:
                    i += 2
                    continue
                if source[i] == quote:
                    i += 1
                    break
                i += 1
        else:
            i += 1
    return "".join(out)


def read_parenthesized(source: str, open_index: int) -> tuple[str, int]:
    """从 `(` 开始读到配对的 `)`，返回（括号内文本, `)` 之后的下标）。"""
    depth = 0
    i = open_index
    length = len(source)
    start = open_index + 1
    while i < length:
        char = source[i]
        if char == "\"":
            i += 1
            while i < length:
                if source[i] == "\\":
                    i += 2
                    continue
                if source[i] == "\"":
                    break
                i += 1
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[start:i], i + 1
        i += 1
    raise ParseError("unbalanced parentheses in annotation")


def annotation_arguments(source: str, name_end: int) -> tuple[str | None, int]:
    """读取注解参数；无参数返回 (None, 注解结束位置)。"""
    i = name_end
    length = len(source)
    while i < length and source[i].isspace():
        i += 1
    if i < length and source[i] == "(":
        return read_parenthesized(source, i)
    return None, name_end


def mapping_path_from_args(args: str | None, where: str) -> str:
    """从 mapping 注解参数里取单个路径（`value=` / `path=` / 裸字符串）。"""
    if args is None or not args.strip():
        return ""
    body = args.strip()
    if body.startswith("{"):
        raise ParseError(f"{where}: mapping 路径数组不受支持：{body[:80]}")
    named = re.match(r"^(?:value|path)\s*=\s*\"((?:[^\"\\]|\\.)*)\"", body)
    if named:
        return named.group(1)
    direct = re.match(r"^\"((?:[^\"\\]|\\.)*)\"", body)
    if direct:
        return direct.group(1)
    raise ParseError(f"{where}: 无法解析 mapping 路径：{body[:80]}")


def _member_boundary(source: str, start: int, end: int) -> bool:
    """注解与方法声明之间出现 `{` `}` `;` → 它不属于这个成员（如类级注解）。"""
    return any(char in "{};" for char in source[start:end])


def parse_controller(path: Path, root: Path) -> tuple[list[dict], list[dict]]:
    """返回（端点列表, @PreAuthorize 列表）。

    端点 = 类级 @RequestMapping 与方法级 @(Get|Post|Put|Delete|Patch)Mapping 拼出的
    (HTTP 方法, 路径)；@PreAuthorize 通过其后最近的方法声明关联到端点。
    """
    rel = _display_path(path, root)
    source = strip_java_comments(path.read_text(encoding="utf-8", errors="replace"))

    class_match = CLASS_REQUEST_MAPPING.search(source)
    class_path = ""
    class_end = 0
    if class_match:
        args, class_end = annotation_arguments(source, class_match.end())
        class_path = mapping_path_from_args(args, f"{rel} 类级 @RequestMapping")

    endpoints: list[dict] = []
    endpoint_by_declaration: dict[int, dict] = {}
    for match in MAPPING_ANNOTATION.finditer(source):
        if match.start() < class_end:
            continue
        http_method = match.group(1).upper()
        args, ann_end = annotation_arguments(source, match.end())
        sub_path = mapping_path_from_args(args, f"{rel} @{http_method}Mapping")
        declaration = JAVA_METHOD_DECL.search(source, ann_end, ann_end + _METHOD_LOOKUP_WINDOW)
        if not declaration:
            # 方法声明可能落在很长的 javadoc / 注解块之后：跳过这个端点并告警，
            # 而不是让整轮检查失败（该端点的注解会因绑定不到端点而报 CONCERN）。
            warn(
                f"{rel}: @{http_method}Mapping({sub_path}) 之后 "
                f"{_METHOD_LOOKUP_WINDOW} 字符内找不到方法声明，跳过该端点"
            )
            continue
        endpoint = {
            "file": rel,
            "javaMethod": declaration.group(1),
            "httpMethod": http_method,
            "path": join_paths(class_path, sub_path),
        }
        endpoints.append(endpoint)
        endpoint_by_declaration[declaration.start()] = endpoint

    annotations: list[dict] = []
    for match in PRE_AUTHORIZE.finditer(source):
        args, ann_end = annotation_arguments(source, match.end())
        line = source.count("\n", 0, match.start()) + 1
        spel = ""
        if args is not None:
            spel = args.strip()
            # 去掉注解参数外层的 Java 字符串字面量引号。
            if len(spel) >= 2 and spel[0] == '"' and spel[-1] == '"':
                spel = spel[1:-1].strip()
        endpoint = None
        declaration = JAVA_METHOD_DECL.search(source, ann_end, ann_end + _METHOD_LOOKUP_WINDOW)
        if declaration and not _member_boundary(source, ann_end, declaration.start()):
            endpoint = endpoint_by_declaration.get(declaration.start())
        annotations.append(
            {"file": rel, "line": line, "spel": spel, "endpoint": endpoint}
        )
    return endpoints, annotations


def _display_path(path: Path, root: Path) -> str:
    """诊断里用的相对路径（相对仓库根，正斜杠）。"""
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def controller_files(controllers_dir: Path, root: Path) -> list[Path]:
    """扫描 controller 源文件。

    `--controllers` 指向仓库根时只扫 platform/scm/ecom/lms 四域；指向具体目录时
    递归扫该目录。`.vmcheck` / `enterprise-agent` / `cognora-aura-web` / `target` /
    `node_modules` / `.git` / `.superpowers` 下的文件一律排除。
    """
    if controllers_dir.resolve() == root.resolve():
        search_roots = [root / domain for domain in DOMAINS if (root / domain).is_dir()]
    else:
        search_roots = [controllers_dir]
    files: list[Path] = []
    for base in search_roots:
        if not base.is_dir():
            continue
        for path in base.rglob("*Controller.java"):
            try:
                parts = path.resolve().relative_to(root.resolve()).parts
            except ValueError:
                parts = path.resolve().parts
            if any(part in EXCLUDED_FRAGMENTS for part in parts):
                continue
            files.append(path)
    return sorted(files)


# ---------------------------------------------------------------------------
# 目录解析与匹配（与映射工具同一套语义）
# ---------------------------------------------------------------------------
def parse_catalog(
    catalog_dir: Path,
) -> tuple[dict[tuple[str, str], dict], set[str], list[str], int, int]:
    """返回（(url, method) 索引, 全部权限码, 备注, 目录行数, 被跳过的字面量数）。

    - 同一 (url, method) 绑到**不同**权限码 → ParseError（目录数据冲突，退出 1）。
    - 同一 (url, method) 同码重复登记 → 去重 + 备注（目录里已有一条已知重复）。
    """
    index: dict[tuple[str, str], dict] = {}
    codes: set[str] = set()
    notes: list[str] = []
    rows = 0
    skipped_literals = 0
    for sql_path in sorted(catalog_dir.glob("*.sql")):
        raw = sql_path.read_text(encoding="utf-8", errors="replace")
        # 先剥掉 `--` 行注释（seed 里有中文注释，含 mojibake，按 replace 读入不崩溃）。
        text = re.sub(r"--[^\n]*", "", raw)
        file_codes, file_skipped = _permission_codes(text)
        codes.update(file_codes)
        skipped_literals += file_skipped
        spans: list[tuple[int, int]] = []
        for pattern, (code_group, url_group, method_group) in zip(
            _CATALOG_PATTERNS, _CATALOG_GROUPS
        ):
            for match in pattern.finditer(text):
                if any(start <= match.start() < end for start, end in spans):
                    continue
                spans.append((match.start(), match.end()))
                code = match.group(code_group)
                url = normalize_path(match.group(url_group))
                method = match.group(method_group).upper()
                if not url.startswith("/"):
                    raise ParseError(
                        f"{sql_path.name}: {code} 的 url 不是绝对路径（{url}），"
                        f"目录形状判定可能有误"
                    )
                rows += 1
                codes.add(code)
                key = (url, method)
                existing = index.get(key)
                if existing is None:
                    index[key] = {"code": code, "url": url, "method": method, "file": sql_path.name}
                elif existing["code"] != code:
                    raise ParseError(
                        f"目录里同一 (url, method) 对应多个权限码：{method} {url} -> "
                        f"{existing['code']}（{existing['file']}） / "
                        f"{code}（{sql_path.name}）"
                    )
                else:
                    notes.append(
                        f"catalog: `{method} {url}`（`{code}`）重复登记于 "
                        f"`{existing['file']}` 与 `{sql_path.name}`，按一条处理"
                    )
    return index, codes, sorted(set(notes)), rows, skipped_literals


def _permission_codes(text: str) -> tuple[set[str], int]:
    """取 `INSERT INTO sys_permission` 语句块里每个权限行的权限码。

    seed 里所有 sys_permission 插入的元组都以权限码开头（`('code', ...)`），
    api_path 为 NULL 时该行同样属于目录 —— 只是没有 (url, method) 绑定。

    只接受**像权限码**的字面量（权限码一律是 `domain:resource:action`，含 `:`）。
    将来若有 seed 把 `permission_code` 挪到别的列，首个字面量就不再是权限码；
    这时宁可跳过（该码按「目录里不存在」处理 → ERROR）也不要把它当成有效权限码，
    否则「码存在但无绑定」的 WARNING 会被误判成 ERROR。跳过的数量会回报给调用方。
    """
    codes: set[str] = set()
    skipped = 0
    for insert in _PERMISSION_INSERT.finditer(text):
        end = text.find(";", insert.end())
        region = text[insert.end():] if end < 0 else text[insert.end():end]
        for row in _PERMISSION_ROW_CODE.finditer(region):
            literal = row.group(1).replace("''", "'")
            if ":" not in literal:
                # 不像权限码（权限码是 domain:resource:action）：跳过并计数。
                skipped += 1
                continue
            codes.add(literal)
    return codes, skipped


def catalog_lookup(index: dict[tuple[str, str], dict], path: str, method: str) -> dict | None:
    """在目录里为端点找权限码。

    精确 (url, method) → 结构匹配（占位符对占位符）→ 方法通配 `*` → Ant 回退，
    得分越高越具体（与映射工具同一套打分）。
    """
    candidates: list[tuple[int, str, dict]] = []
    endpoint_segments = split_segments(path)
    for (_url, row_method), row in index.items():
        if row_method != "*" and row_method != method:
            continue
        score = _score_match(split_segments(row["url"]), endpoint_segments)
        if score is not None:
            candidates.append((score, row["code"], row))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[0], item[2]["url"], item[1]))
    best = candidates[0]
    ambiguous = {code for score, code, _row in candidates if score == best[0]}
    if len(ambiguous) > 1:
        print(
            f"NOTE 端点 {method} {path} 同时命中多个权限码：{sorted(ambiguous)}"
            f"（取 {best[1]}）"
        )
    return best[2]


def _score_match(pattern_segments: list[str], endpoint_segments: list[str]) -> int | None:
    """目录 URL 与端点路径的结构匹配打分；不匹配返回 None。"""
    memo: dict[tuple[int, int], int | None] = {}

    def walk(pi: int, si: int) -> int | None:
        key = (pi, si)
        if key in memo:
            return memo[key]
        result: int | None
        if pi == len(pattern_segments):
            result = 0 if si == len(endpoint_segments) else None
        elif pattern_segments[pi] == "**":
            options = [walk(pi + 1, si)]
            if si < len(endpoint_segments):
                options.append(walk(pi, si + 1))
            result = max((value for value in options if value is not None), default=None)
        elif si == len(endpoint_segments):
            result = None
        else:
            score = _segment_score(pattern_segments[pi], endpoint_segments[si])
            tail = walk(pi + 1, si + 1) if score is not None else None
            result = None if tail is None else score + tail
        memo[key] = result
        return result

    return walk(0, 0)


def _segment_score(pattern: str, endpoint: str) -> int | None:
    """单段打分：字面量 3、同名占位符 2、异名占位符 1、`*` 0、不匹配 None。"""
    if pattern == "*":
        return 0
    if is_placeholder(pattern):
        if not is_placeholder(endpoint):
            return 0
        # 占位符名一致时更具体：/api/v1/orders/{id} 与 order-center 的 {orderNo}
        # 结构等价，靠变量名区分 scm-order 与 ecom-order-center 的两条目录行。
        return 2 if pattern == endpoint else 1
    if is_placeholder(endpoint):
        return None
    if pattern == endpoint:
        return 3
    if segment_matches(pattern, endpoint):
        return 0
    return None


# ---------------------------------------------------------------------------
# 检查
# ---------------------------------------------------------------------------
def evaluate(
    annotation: dict,
    index: dict[tuple[str, str], dict],
    codes: set[str],
) -> tuple[str, str]:
    """返回 (severity, message)；severity ∈ {"ok", "error", "warning", "concern"}。"""
    where = f"{annotation['file']}:{annotation['line']}"
    spel = annotation["spel"]
    endpoint = annotation["endpoint"]

    authority = HAS_AUTHORITY.match(spel)
    if authority is None:
        # `isAuthenticated()` 与其它 SpEL 形态：完全忽略（不猜语义，也不当成违规）。
        return "ok", ""

    code = authority.group(1)
    if endpoint is None:
        return SEVERITY_CONCERN, (
            f"{where} {code} expected <none: @PreAuthorize 未绑定到任何 request mapping>"
        )

    http_method = endpoint["httpMethod"]
    path = endpoint["path"]

    reason = exemption(path)
    if reason is not None:
        return SEVERITY_ERROR, (
            f"{where} {code} expected <none: {http_method} {path} 命中豁免清单 "
            f"{reason}，不应有权限注解>"
        )

    row = catalog_lookup(index, path, http_method)
    if row is not None:
        if row["code"] != code:
            return SEVERITY_ERROR, (
                f"{where} {code} expected {row['code']} "
                f"（{http_method} {path}，目录 {row['file']}）"
            )
        return "ok", ""

    if code in codes:
        return SEVERITY_WARNING, (
            f"{where} {code} expected <none: 目录里没有 {http_method} {path} 的绑定>"
        )
    return SEVERITY_ERROR, f"{where} {code} expected <none: 目录里不存在该权限码>"


def _force_utf8_output() -> None:
    """把 stdout/stderr 切到 UTF-8。

    Windows 控制台默认编码不是 UTF-8，中文诊断会抛 UnicodeEncodeError；
    CI 里重定向到文件时也能保证输出是 UTF-8（不需要额外设置 PYTHONIOENCODING）。
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8")
        except (ValueError, OSError):  # pragma: no cover - 流不可重配时退回默认行为
            pass


def main(argv: list[str] | None = None) -> int:
    _force_utf8_output()
    parser = argparse.ArgumentParser(
        description="@PreAuthorize 与权限目录的 drift 检查（退出码 0/1）"
    )
    parser.add_argument(
        "--catalog", type=Path, help="seed SQL 目录（默认 <root>/scripts/db/seed）"
    )
    parser.add_argument(
        "--controllers",
        type=Path,
        help="controller 扫描目录（默认 <root>，只扫 platform/scm/ecom/lms 四域）",
    )
    parser.add_argument("--root", type=Path, help="仓库根（用于相对路径与默认值）")
    options = parser.parse_args(argv)

    root = (options.root or ROOT).resolve()
    catalog_dir = (options.catalog or root / "scripts/db/seed").resolve()
    controllers_dir = (options.controllers or root).resolve()

    if not catalog_dir.is_dir():
        print(f"ERROR catalog 目录不存在：{catalog_dir}")
        return 1
    if not controllers_dir.is_dir():
        print(f"ERROR controllers 目录不存在：{controllers_dir}")
        return 1

    try:
        _self_check_exempt_lists()
        index, codes, catalog_notes, catalog_rows, skipped_literals = parse_catalog(catalog_dir)
        annotations: list[dict] = []
        endpoint_count = 0
        for java_file in controller_files(controllers_dir, root):
            endpoints, file_annotations = parse_controller(java_file, root)
            endpoint_count += len(endpoints)
            annotations.extend(file_annotations)
    except ParseError as exc:
        print(f"ERROR parse: {exc}")
        return 1
    except (OSError, UnicodeError) as exc:
        # 读 seed / controller 失败（编码、权限、磁盘…）：给一行干净诊断，不要 traceback。
        print(f"ERROR parse: 读取目录或源码失败：{exc.__class__.__name__}: {exc}")
        return 1

    for note in catalog_notes:
        print(f"NOTE {note}")
    if skipped_literals:
        print(
            f"NOTE 目录里有 {skipped_literals} 个不像权限码的字面量（权限码应为 "
            f"domain:resource:action），已跳过：seed 的列顺序可能变了"
        )

    diagnostics: list[tuple[str, str, int]] = []
    checked = 0
    for annotation in annotations:
        severity, message = evaluate(annotation, index, codes)
        if HAS_AUTHORITY.match(annotation["spel"]):
            checked += 1
        if severity != "ok":
            diagnostics.append((severity, message, annotation["line"]))

    diagnostics.sort(key=lambda item: (item[1].split(":", 1)[0], item[2], item[1]))
    errors = [message for severity, message, _line in diagnostics if severity == SEVERITY_ERROR]
    warnings = [
        message for severity, message, _line in diagnostics if severity == SEVERITY_WARNING
    ]
    concerns = [
        message for severity, message, _line in diagnostics if severity == SEVERITY_CONCERN
    ]
    for message in errors:
        print(f"ERROR {message}")
    for message in warnings:
        print(f"WARNING {message}")
    for message in concerns:
        print(f"CONCERN {message}")

    print(
        f"checked={checked} hasAuthority annotations, endpoints={endpoint_count}, "
        f"catalog_rows={catalog_rows} (url+method), unique_pairs={len(index)}, "
        f"catalog_codes={len(codes)} (incl. api_path NULL), "
        f"errors={len(errors)}, warnings={len(warnings)}, concerns={len(concerns)}"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
