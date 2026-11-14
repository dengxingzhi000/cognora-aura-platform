# 权限解析契约（短 Token 之后）

> 适用范围：`platform/common/web` 的 `JwtAuthenticationFilter` 与 `ApiAccessControlFilter`
> 状态：已随「阶段一（P0-2）」落地
> 关联：`architecture-review.md` P0-2、`docs/security/PERMISSION_COVERAGE_MATRIX.md`

## 1. 背景

访问令牌不再内嵌权限清单（一个租户管理员的权限码有数百条，签出来的 token 约 26KB，超过浏览器
单 cookie 的 ~4KB 上限，cookie 被静默丢弃、登录后一刷新就掉线）。改为：

- token 只保留 `roles`（数量少）与 `tenantId` / `aud` 等声明，整体约 1KB；
- 权限由服务端**逐请求**解析：`JwtAuthenticationFilter` → `PermissionService.findAllPermissionsByUserId`。

## 2. 权限来源与优先级

| 顺序 | 来源 | 说明 |
|---|---|---|
| 1 | Token 的 `permissions` 声明 | 仅短化之前签发的旧 token 会有。命中即使用，**但不标记为已解析**，端点级校验仍会自己做一次权威查询——避免 token 生命周期内被撤销的权限继续生效。 |
| 2 | `PermissionService.findAllPermissionsByUserId` | 新 token 的唯一权威来源。成功后把结果挂到请求属性 `cognora.security.permissions-resolved`。 |
| 3 | 无需权限的路由 | 见第 3 节，返回空集合且**不查服务**。 |

`ApiAccessControlFilter` 在请求属性存在时直接复用第 2 步的结果（指标
`security.access.permissions.reused`），把「每请求两次用户权限查询」降为一次；属性不存在时
（例如旧 token 或非 JWT 认证路径）它仍自己做一次权威查询。

## 3. 失败语义（本契约的核心）

**查不到权限 = 拒绝，不是放行，也不是静默降级为「仅角色」。**

| 情形 | 结果 |
|---|---|
| 需要权限的路由 + 权限服务异常/超时/无 `PermissionService` bean | `503`，错误码 `PERMISSION_SERVICE_UNAVAILABLE`（`JwtAuthenticationFilter` 直接应答，不再进入业务链路） |
| 需要权限的路由 + 权限服务正常但用户确实没有该权限 | `403`，错误码 `ACCESS_DENIED`（由 `ApiAccessControlFilter` 判定） |
| 需要权限的路由 + 权限目录里没有登记该 URL | `403`，错误码 `API_NOT_REGISTERED`（`unregisteredEndpointPolicy=DENY`） |
| 无需权限的路由（如下）+ 权限服务异常 | 请求正常继续，权限集合为空，仅带 token 中的 `roles` |
| 服务间签名已校验通过（`internalSignaturePaths` 等） | 跳过权限查询，直接放行（由 HMAC 负责授权） |

「无需权限的路由」= `ApiAccessControlFilter` 在查询权限前就返回的分支：`cognora.security.api-filter.enabled=false`、
`whitelist`、`exemptPaths`（且不命中 `exemptPathExclusions`）、或已带
`cognora.security.internal-signature-verified` 请求属性。这些是登出、会话自助、个人资料/改密、
支付渠道回调等**不承担角色权限语义**的端点——保留它们可用，运维才能在 platform-system 故障期间
登出或轮换凭据。

> 为什么不是「仅角色继续」：角色是从 token 里读的、无法声明权限；继续放行会让一次抖动变成一大片
> 403（看起来像权限配错），对 `hasRole` 类判断还可能变成「用没出示过的证据放行」。故障就应该是
> 明显的 503。

## 4. 指标与告警

| 指标 | 位置 | 含义 |
|---|---|---|
| `security.permissions.resolved` | 每个业务服务 | 权限解析成功次数 |
| `security.permissions.resolve_failed` | 每个业务服务 | 权限服务返回失败 |
| `security.permissions.unavailable` | 每个业务服务 | 因权限不可用而返回 503 |
| `security.access.permissions.reused` | 每个业务服务 | 复用了 JWT 过滤器已解析的权限集合 |
| `security.access.permission_service_unavailable` | 每个业务服务 | `ApiAccessControlFilter` 因权限不可用返回 503 |
| `security.access.denied` | 每个业务服务 | 有权限目录但用户不持有 |

`security.permissions.unavailable` 或 `security.access.permission_service_unavailable` 非 0
持续出现，说明 platform-system 权限查询不可用；`security.access.denied` 突增而权限服务正常，才是
真的权限配置/授予问题。

## 5. 权限撤销生效时延

- 本链路不在调用方缓存用户权限；每个请求都会向 platform-system 查询。
- platform-system 侧 `userPermissions` 缓存由 `UserAuthorizationCacheEvictor` 在角色/权限写操作时
  整体 `clear()`，因此**撤销在下一次请求即生效**。
- 旧 token（内嵌权限）在端点级校验上仍会做一次权威查询，所以撤销同样在下一次请求生效；token
  携带的 `roles` 只在角色被移除后仍可能在 token 有效期内保留，直到 token 过期或主动撤销。

## 6. 待实测（不在本次静态修复范围内）

- 每请求 P99 增量：需要平台全链路压测（platform-system 在线 + Redis/Caffeine 命中率），当前仅有
  静态证据；短化前后的对比请在 `docs/project-management/PERFORMANCE_BENCHMARK.md` 中落成真实数据。
- platform-system 宕机演练：本契约给出的是单元级行为（`JwtAuthenticationFilterPermissionResolutionTest`、
  `SecurityFiltersTest`、`PermissionServiceFailureFilterTest`），端到端演练仍需在预发环境执行。

## 7. 改动约束

- 新增「服务间」端点请加入 `cognora.security.api-filter.internal-signature-paths`，不要加入
  `exempt-paths`：前者要求 HMAC 签名，后者是直接放行。
- 不要把权限清单塞回 token：`JwtUtilsTokenSizeTest` 会在超过 4KB 时失败。
- 任何「查不到权限就当有权限」的降级都必须先改本契约并说明理由。
