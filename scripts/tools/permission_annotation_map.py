#!/usr/bin/env python3
"""权限目录 ↔ Controller 端点映射工具（D1 缺口报告产出）。

用法：py -3 scripts/tools/permission_annotation_map.py

产出（均入库）：
  * docs/superpowers/specs/2026-11-12-permission-mapping.json
      机器可读映射，供 Task 3-10 的注解扫描与 CI 护栏消费；
  * docs/superpowers/specs/2026-11-12-permission-catalog-gaps.md
      D1 缺口报告：未登记到权限目录、且不在任何豁免清单里的端点。

口径：
  * 端点 = `platform|scm|ecom|lms` 四域下 `*Controller.java` 的类级 `@RequestMapping`
    与方法级 `@(Get|Post|Put|Delete|Patch)Mapping` 拼出的完整路径；
  * `code` = seed 目录行里的权限码。目录行的唯一事实源是 `scripts/db/seed/*.sql`：
    行内同时出现 URL 与 HTTP 方法（GET/POST/PUT/DELETE/PATCH/*）才算目录行，
    角色、菜单等其它 insert 不会被误当成目录；
  * `exempt` = 命中 `ApiAccessControlProperties` 的 whitelist / exempt-paths /
    internal-signature-paths / signature-or-permission-paths（Ant 匹配）；
  * 缺口 = `code is None and exempt is False`。

CI 护栏 `scripts/ci/check_permission_annotation_drift.py` 用同一套目录/controller/豁免
规则校验 `@PreAuthorize`；两个脚本代码各自独立、常量必须同步。

仅使用标准库。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

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

SEED_DIR = ROOT / "scripts/db/seed"
OUT_JSON = ROOT / "docs/superpowers/specs/2026-11-12-permission-mapping.json"
OUT_MD = ROOT / "docs/superpowers/specs/2026-11-12-permission-catalog-gaps.md"

# mapping 注解之后向前找方法声明的窗口（字符数）。javadoc / 注解块很长时容易超出，
# 窗口不足会误判「找不到方法声明」；仍然找不到就跳过该端点并告警，而不是中断整轮映射。
# 与 scripts/ci/check_permission_annotation_drift.py 的同名常量必须一致
# （一致性由 scripts/ci/test_permission_rules_conformance.py 钉住）。
METHOD_LOOKUP_WINDOW = 8000

# ---------------------------------------------------------------------------
# 豁免清单：逐条抄自
# platform/common/web/src/main/java/com/cognora/platform/common/security/config/ApiAccessControlProperties.java
# （2026-11-13 的当前默认值，字段顺序 whitelist → exemptPaths → exemptPathExclusions →
#   internalSignaturePaths → signatureOrPermissionPaths）。
# 语义见 ApiAccessControlFilter#isWhitelisted/#isExempt 与 RequestPathMatcher：
# 路径先规范化（去矩阵参数、百分号解码、去尾斜杠），再按 Ant 风格匹配
# （`**` 任意段、`*` 单段、`?` 单字符）。改默认值时必须同步改这里。
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
# SQL 目录行解析
#
# seed 里的目录行有四种历史写法，URL 与 HTTP 方法的位置不同，但都插入
# sys_permission(api_path, http_method)：
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
_CATALOG_GROUPS = (
    # (url group index, method group index, shape label)
    (4, 5, "8-field"),
    (4, 5, "7-field"),
    (3, 4, "6-field"),
    (3, 4, "typed"),
)

JAVA_METHOD_DECL = re.compile(
    r"(?:public|protected|private)\s+[^;{}=()]*?\b([A-Za-z_]\w*)\s*\("
)
MAPPING_ANNOTATION = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b")
CLASS_REQUEST_MAPPING = re.compile(r"@RequestMapping\b")


class ParseError(RuntimeError):
    """源码结构与预期不符，宁可失败也不产出错误映射。"""


def warn(message: str) -> None:
    print(f"WARNING: {message}", file=sys.stderr)


# ---------------------------------------------------------------------------
# 路径工具
# ---------------------------------------------------------------------------
def normalize_path(path: str) -> str:
    """规范化 controller 拼出的路径：单斜杠、带前导斜杠、去尾斜杠。"""
    if not path:
        return "/"
    normalized = re.sub(r"/+", "/", "/" + path.strip())
    if len(normalized) > 1:
        normalized = normalized.rstrip("/")
    return normalized


def join_paths(base: str, sub: str) -> str:
    if not sub:
        return normalize_path(base)
    if not base:
        return normalize_path(sub)
    return normalize_path(base.rstrip("/") + "/" + sub.lstrip("/"))


def split_segments(path: str) -> list[str]:
    return [segment for segment in normalize_path(path).split("/") if segment]


def is_placeholder(segment: str) -> bool:
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
    """按 Ant 语义匹配已规范化的路径（`**` 任意段、`*` 单段、`?` 单字符）。"""
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
            result = segment_matches(pattern_segments[pi], path_segments[si]) and walk(pi + 1, si + 1)
        memo[key] = result
        return result

    return walk(0, 0)


def matches_any(path: str, patterns: list[str]) -> bool:
    return any(ant_matches(pattern, path) for pattern in patterns if pattern)


# ---------------------------------------------------------------------------
# Java 源码解析
# ---------------------------------------------------------------------------
def strip_java_comments(source: str) -> str:
    """把注释替换成等长空格（保留换行），行号与源文件保持一致。

    字符串/字符字面量的内容原样保留：注解参数里就有字符串。

    注释**置空**而不是删除：删除会让源码变短，而方法声明查找窗口
    （METHOD_LOOKUP_WINDOW）按字符数量计算 —— 两侧算法不同就会量到不同的距离，
    得到不同的端点集合（生成器与 CI 护栏因此会对同一份源码给出不同结论）。
    置空后两侧量到的是同一段距离，端点集合一致；一致性由
    scripts/ci/test_permission_rules_conformance.py 钉住。
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
    """从 `(` 开始读取到配对的 `)`，返回（括号内文本, `)` 之后的下标）。"""
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
        args, end = read_parenthesized(source, i)
        return args, end
    return None, name_end


def mapping_path_from_args(args: str | None, where: str) -> str:
    """从 mapping 注解参数里取单个路径。"""
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


def parse_controller(path: Path) -> tuple[str, list[dict]]:
    rel = path.relative_to(ROOT).as_posix()
    source = strip_java_comments(path.read_text(encoding="utf-8", errors="replace"))

    class_match = CLASS_REQUEST_MAPPING.search(source)
    class_path = ""
    class_end = 0
    if class_match:
        args, class_end = annotation_arguments(source, class_match.end())
        class_path = mapping_path_from_args(args, f"{rel} 类级 @RequestMapping")
    elif MAPPING_ANNOTATION.search(source):
        warn(f"{rel}: 缺少类级 @RequestMapping，按空前缀处理")

    endpoints: list[dict] = []
    for match in MAPPING_ANNOTATION.finditer(source):
        if match.start() < class_end:
            continue
        http_method = match.group(1).upper()
        args, ann_end = annotation_arguments(source, match.end())
        sub_path = mapping_path_from_args(args, f"{rel} @{http_method}Mapping")
        declaration = JAVA_METHOD_DECL.search(source, ann_end, ann_end + METHOD_LOOKUP_WINDOW)
        if not declaration:
            # 方法声明可能落在很长的 javadoc / 注解块之后：跳过这个端点并告警，
            # 而不是让整轮映射失败（与 CI 护栏同一套处理）。
            warn(
                f"{rel}: @{http_method}Mapping({sub_path}) 之后 "
                f"{METHOD_LOOKUP_WINDOW} 字符内找不到方法声明，跳过该端点"
            )
            continue
        endpoints.append(
            {
                "file": rel,
                "class": path.stem,
                "javaMethod": declaration.group(1),
                "httpMethod": http_method,
                "path": join_paths(class_path, sub_path),
            }
        )
    return rel, endpoints


def controller_files() -> list[Path]:
    files: list[Path] = []
    for domain in DOMAINS:
        domain_dir = ROOT / domain
        if not domain_dir.is_dir():
            continue
        for path in domain_dir.rglob("*Controller.java"):
            rel_parts = path.relative_to(ROOT).parts
            if any(part in EXCLUDED_FRAGMENTS for part in rel_parts):
                continue
            files.append(path)
    return sorted(files)


def service_dir(rel_file: str) -> str:
    marker = "/src/main/java/"
    index = rel_file.find(marker)
    if index < 0:
        raise ParseError(f"{rel_file}: 不在 src/main/java 下，无法确定服务目录")
    return rel_file[:index]


# ---------------------------------------------------------------------------
# 目录解析与匹配
# ---------------------------------------------------------------------------
def parse_catalog() -> tuple[list[dict], list[str]]:
    rows: list[dict] = []
    notes: list[str] = []
    seen_codes: dict[str, tuple[str, str, str]] = {}
    for sql_path in sorted(SEED_DIR.glob("*.sql")):
        text = re.sub(r"--[^\n]*", "", sql_path.read_text(encoding="utf-8", errors="replace"))
        spans: list[tuple[int, int]] = []
        for pattern, (url_group, method_group, shape) in zip(_CATALOG_PATTERNS, _CATALOG_GROUPS):
            for match in pattern.finditer(text):
                if any(start <= match.start() < end for start, end in spans):
                    continue
                spans.append((match.start(), match.end()))
                code = match.group(1)
                raw_url = match.group(url_group)
                method = match.group(method_group).upper()
                if not raw_url.strip().startswith("/"):
                    # 结构不合法 → 宁可失败，也不产出错误结论。
                    # 注意：这里校验的是 **seed 里的原始值**，不是 normalize 之后的值
                    # （normalize_path 会补前导斜杠，用它校验等于形同虚设）。
                    raise ParseError(
                        f"{sql_path.name}: {code} 的 url 不是绝对路径（{raw_url}），"
                        f"目录形状判定可能有误"
                    )
                url = normalize_path(raw_url)
                rows.append(
                    {
                        "code": code,
                        "url": url,
                        "method": method,
                        "file": sql_path.name,
                        "shape": shape,
                    }
                )
                previous = seen_codes.get(code)
                if previous is None:
                    seen_codes[code] = (url, method, sql_path.name)
                elif previous[:2] == (url, method):
                    notes.append(
                        f"`{method} {url}`（`{code}`）重复登记："
                        f"`{previous[2]}` 与 `{sql_path.name}`，按一条处理"
                    )
                else:
                    notes.append(
                        f"权限码 `{code}` 重复登记且 URL/方法不同："
                        f"{previous[1]} {previous[0]}（`{previous[2]}`）与 "
                        f"{method} {url}（`{sql_path.name}`）"
                    )
    return rows, notes


def build_catalog_index(rows: list[dict]) -> dict[tuple[str, str], dict]:
    """按 (url, method) 去重；同一个码的重复行只保留一条并记入备注。"""
    index: dict[tuple[str, str], dict] = {}
    for row in rows:
        key = (row["url"], row["method"])
        existing = index.get(key)
        if existing is None:
            index[key] = row
        elif existing["code"] != row["code"]:
            raise ParseError(
                f"目录里同一 (url, method) 对应多个权限码：{row['method']} {row['url']} -> "
                f"{existing['code']}（{existing['file']}） / {row['code']}（{row['file']}）"
            )
    return index


def catalog_lookup(index: dict[tuple[str, str], dict], path: str, method: str) -> dict | None:
    """在目录里为端点找权限码。

    优先精确 (url, method)，其次结构匹配（占位符对占位符）、方法通配 `*`、
    最后按 Ant 语义回退（目录模板占位符也可以覆盖控制器的字面量段）。
    得分越高越具体。
    """
    candidates: list[tuple[int, str, dict]] = []
    endpoint_segments = split_segments(path)
    for (url, row_method), row in index.items():
        if row_method != "*" and row_method != method:
            continue
        score = _score_match(split_segments(url), endpoint_segments)
        if score is not None:
            candidates.append((score, row["code"], row))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[0], item[2]["url"], item[1]))
    best = candidates[0]
    ambiguous = {code for score, code, _ in candidates if score == best[0]}
    if len(ambiguous) > 1:
        warn(f"端点 {method} {path} 同时命中多个权限码：{sorted(ambiguous)}（取 {best[1]}）")
    return best[2]


def _score_match(pattern_segments: list[str], endpoint_segments: list[str]) -> int | None:
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
    if pattern == "*":
        return 0
    if is_placeholder(pattern):
        if not is_placeholder(endpoint):
            return 0
        # 占位符名一致时更具体：/api/v1/orders/{id} 与 order-center 的 {orderNo} 模板
        # 在结构上等价，靠变量名区分 scm-order 与 ecom-order-center 的两条目录行。
        return 2 if pattern == endpoint else 1
    if is_placeholder(endpoint):
        return None
    if pattern == endpoint:
        return 3
    if segment_matches(pattern, endpoint):
        return 0
    return None


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


def _placeholder_probe_path(path: str) -> str:
    """把 `{var}` 换成普通段，让豁免清单的 `*`/`**` 能匹配到占位符段。"""
    segments = ["_var_" if is_placeholder(segment) else segment for segment in split_segments(path)]
    return "/" + "/".join(segments)


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
            raise SystemExit(f"{name} 为空，抄写 ApiAccessControlProperties 时漏了内容")
        for pattern in patterns:
            if not pattern.startswith("/"):
                raise SystemExit(f"{name} 含非绝对路径项：{pattern}")
        if len(set(patterns)) != len(patterns):
            raise SystemExit(f"{name} 含重复项")


# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------
def build_mapping() -> tuple[dict, list[str], dict[str, int]]:
    catalog_rows, catalog_notes = parse_catalog()
    catalog = build_catalog_index(catalog_rows)
    catalog_stats = {
        "rows": len(catalog_rows),
        "pairs": len(catalog),
        "codes": len({row["code"] for row in catalog_rows}),
    }

    services: dict[str, dict] = {}
    for java_file in controller_files():
        rel, endpoints = parse_controller(java_file)
        service = service_dir(rel)
        bucket = services.setdefault(service, {"endpoints": []})
        for endpoint in endpoints:
            row = catalog_lookup(catalog, endpoint["path"], endpoint["httpMethod"])
            reason = exemption(endpoint["path"])
            bucket["endpoints"].append(
                {
                    **endpoint,
                    "code": row["code"] if row else None,
                    "exempt": reason is not None,
                    "exemptReason": reason,
                }
            )

    for bucket in services.values():
        bucket["endpoints"].sort(key=lambda item: (item["path"], item["httpMethod"], item["file"]))
    ordered = {key: services[key] for key in sorted(services)}

    _check_duplicate_endpoints(ordered)
    return {"services": ordered}, catalog_notes, catalog_stats


def _check_duplicate_endpoints(services: dict[str, dict]) -> None:
    seen: dict[tuple[str, str, str], str] = {}
    for service, bucket in services.items():
        for endpoint in bucket["endpoints"]:
            key = (service, endpoint["httpMethod"], endpoint["path"])
            if key in seen:
                warn(
                    f"同一服务内重复的 request mapping：{endpoint['httpMethod']} "
                    f"{endpoint['path']}（{seen[key]} / {endpoint['file']}）"
                )
            else:
                seen[key] = endpoint["file"]


def render_report(mapping: dict, catalog_notes: list[str], catalog_stats: dict[str, int]) -> str:
    services = mapping["services"]
    total = sum(len(bucket["endpoints"]) for bucket in services.values())
    all_endpoints = [endpoint for bucket in services.values() for endpoint in bucket["endpoints"]]
    mapped = [endpoint for endpoint in all_endpoints if endpoint["code"]]
    exempt = [endpoint for endpoint in all_endpoints if endpoint["exempt"]]
    gaps = [endpoint for endpoint in all_endpoints if not endpoint["code"] and not endpoint["exempt"]]
    mapped_and_exempt = [endpoint for endpoint in all_endpoints if endpoint["code"] and endpoint["exempt"]]

    lines: list[str] = []
    lines.append("# 权限目录缺口报告（D1）")
    lines.append("")
    lines.append(
        "> 生成：`py -3 scripts/tools/permission_annotation_map.py`  "
    )
    lines.append(
        "> 机器可读映射：`docs/superpowers/specs/2026-11-12-permission-mapping.json`  "
    )
    lines.append(
        "> 输入：`platform|scm|ecom|lms` 四域 `*Controller.java` + `scripts/db/seed/*.sql`"
    )
    lines.append("")
    lines.append("## 口径")
    lines.append("")
    lines.append(
        "- 端点 = 控制器类级 `@RequestMapping` 与方法级 "
        "`@(Get|Post|Put|Delete|Patch)Mapping` 拼出的（HTTP 方法, 路径）。"
    )
    lines.append(
        "- `code` 来自 seed 目录行（`scripts/db/seed/*.sql` 中同时含 URL 与 HTTP 方法的行）。"
        "`{var}` 占位符按结构匹配：目录 `{id}` 与控制器 `{orderNo}` 等价；"
        "目录里的 `*` / `**` 按 Ant 语义匹配。"
    )
    lines.append(
        "- 先判豁免，顺序与 `ApiAccessControlFilter` 一致：`whitelist` → "
        "`exemptPaths`（先扣 `exemptPathExclusions`）→ `internalSignaturePaths` → "
        "`signatureOrPermissionPaths`。"
    )
    lines.append(
        "- **缺口 = 未豁免且目录里没有匹配行的端点。** 已登录用户的请求命中这类端点时，"
        "当前会被 `ApiAccessControlFilter` 以 `API_NOT_REGISTERED` 403"
        "（`unregistered-endpoint-policy` 默认 `DENY`）；未解析出用户的请求会直接放行"
        "（过滤器在无用户上下文时跳过端点登记校验）。本轮不为它们补 `@PreAuthorize`。"
    )
    lines.append("")
    lines.append("## 总览")
    lines.append("")
    lines.append("| 指标 | 数量 |")
    lines.append("| --- | ---: |")
    lines.append(f"| 服务（有 controller 的模块） | {len(services)} |")
    lines.append(f"| 端点总数 | {total} |")
    lines.append(f"| seed 目录行 / 唯一 (url, method) 对 | {catalog_stats['rows']} / {catalog_stats['pairs']} |")
    lines.append(f"| 已映射到权限码 | {len(mapped)} |")
    lines.append(f"| 豁免端点 | {len(exempt)} |")
    lines.append(f"| 其中既命中目录又被豁免 | {len(mapped_and_exempt)} |")
    lines.append(f"| **目录缺口** | **{len(gaps)}** |")
    lines.append("")
    lines.append(
        f"`已映射 + 豁免 - 既映射又豁免 + 缺口 = {len(mapped)} + {len(exempt)} - "
        f"{len(mapped_and_exempt)} + {len(gaps)} = {total}`。"
        "「既命中目录又被豁免」的端点（如 `/api/v1/payments/{id}`）运行时先命中豁免，"
        "目录行不参与放行。"
    )
    lines.append("")
    reason_counts: dict[str, int] = {}
    for endpoint in exempt:
        reason_counts[endpoint["exemptReason"]] = reason_counts.get(endpoint["exemptReason"], 0) + 1
    if reason_counts:
        lines.append("豁免端点按规则分布：")
        lines.append("")
        lines.append("| 豁免规则 | 数量 |")
        lines.append("| --- | ---: |")
        for reason in sorted(reason_counts):
            lines.append(f"| `{reason}` | {reason_counts[reason]} |")
        lines.append("")

    lines.append("## 各服务概览")
    lines.append("")
    lines.append("| 服务 | 端点 | 已映射 | 豁免 | 缺口 |")
    lines.append("| --- | ---: | ---: | ---: | ---: |")
    for service, bucket in services.items():
        endpoints = bucket["endpoints"]
        lines.append(
            f"| `{service}` | {len(endpoints)} | "
            f"{sum(1 for endpoint in endpoints if endpoint['code'])} | "
            f"{sum(1 for endpoint in endpoints if endpoint['exempt'])} | "
            f"{sum(1 for endpoint in endpoints if not endpoint['code'] and not endpoint['exempt'])} |"
        )
    lines.append("")

    lines.append("## 缺口明细")
    lines.append("")
    if not gaps:
        lines.append("无缺口。")
        lines.append("")
    for service, bucket in services.items():
        service_gaps = [
            endpoint
            for endpoint in bucket["endpoints"]
            if not endpoint["code"] and not endpoint["exempt"]
        ]
        if not service_gaps:
            continue
        lines.append(f"### `{service}`（{len(service_gaps)} 个）")
        lines.append("")
        lines.append("| HTTP | 路径 | Java 方法 | 源文件 |")
        lines.append("| --- | --- | --- | --- |")
        for endpoint in service_gaps:
            lines.append(
                f"| {endpoint['httpMethod']} | `{endpoint['path']}` | "
                f"`{endpoint['javaMethod']}` | `{endpoint['file']}` |"
            )
        lines.append("")

    if catalog_notes:
        lines.append("## 目录数据备注")
        lines.append("")
        for note in sorted(set(catalog_notes)):
            lines.append(f"- {note}")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    _self_check_exempt_lists()
    mapping, catalog_notes, catalog_stats = build_mapping()

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(
        json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    OUT_MD.write_text(render_report(mapping, catalog_notes, catalog_stats), encoding="utf-8")

    services = mapping["services"]
    total = sum(len(bucket["endpoints"]) for bucket in services.values())
    mapped = sum(1 for bucket in services.values() for endpoint in bucket["endpoints"] if endpoint["code"])
    exempt = sum(1 for bucket in services.values() for endpoint in bucket["endpoints"] if endpoint["exempt"])
    gaps = sum(
        1
        for bucket in services.values()
        for endpoint in bucket["endpoints"]
        if not endpoint["code"] and not endpoint["exempt"]
    )
    print(
        f"catalog_rows={catalog_stats['rows']} unique_pairs={catalog_stats['pairs']} "
        f"services={len(services)} endpoints={total} mapped={mapped} exempt={exempt} gaps={gaps}"
    )
    print(f"wrote {OUT_JSON.relative_to(ROOT).as_posix()}")
    print(f"wrote {OUT_MD.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
