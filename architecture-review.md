# Cognora Aura Platform 架构与产品评审

> 评审角色：资深架构师 / 产品经理视角
> 评审日期：2026-11-10
> 范围：`cognora-aura-platform`（Java 后端）+ 内嵌的 `cognora-aura-web`（Next.js 前端，独立 git 仓库）
> 方式：只读核实代码/配置/文档 + 定向单元测试验证；未连生产、未启全栈、未改动任何代码
> 技能：`architecture-visualization:risk-quality-reviewer`（Business Application Fitness 参考）

---

## 0. 评审前提与证据边界

- **当前工作树不等于已发布基线**：分支 `feat/user-management-fefo` 领先 `origin/master` 1 个提交，且有大量未提交改动（认证短 Token 改造、库存盘点/估值新功能、CI 调整）。本报告区分「已合并基线」与「在研改动」。
- **前端仓库版本**：`cognora-aura-web` 位于 `main`（tag `v0.4.0`），另有未提交的用户管理/2FA 相关改动。
- **验证手段**：定向单元测试全绿——认证/服务间签名 24 项、库存状态机+CSV 9 项、租户 SQL 隔离 16 项。构建 `test-compile` 通过。
- **运行态未知项**：性能 TPS/QPS、可用性 SLO、缓存失效时延、权限链路 P99 增量等，仅有静态证据，标注为「待实测」，不下确定结论。
- **过时报告提醒**：`docs/ARCHITECTURE_AUDIT_2026.md`、`docs/superpowers/specs/2026-06-10-architecture-review.md` 列的多项 P0（scm-common 上帝模块、ShardingSphere、无 Flyway、租户四大缺口）**多数已修复**，不应照旧当现状。

---

## 0.1 阶段一修复记录（2026-11-11，P0-1 / P0-2 / P0-3）

按本文档验收标准执行「阶段一：稳交易与认证」。以下为**已落地并有测试/证据**的改动；未完成项
明确标注，不写成已完成。

| 条目 | 状态 | 落点 | 验证 |
|---|---|---|---|
| P0-1 未验签置 paid | **已修**（含未完成的回调闭环，见下） | `IOrderCenterServiceImpl.applyVerifiedPayment`、`OrderCenterController`、`ApiAccessControlProperties`、`SecurityConfig` | `OrderCenterServiceImplTest`（10 项）+ `OrderCenterPaymentContractTest`（4 项）全绿 |
| P0-2 权限链路失败语义 | **已修**（语义 + 去重 + 契约文档） | `JwtAuthenticationFilter`、`ApiAccessControlFilter`、`docs/security/PERMISSION_RESOLUTION_CONTRACT.md` | `JwtAuthenticationFilterPermissionResolutionTest`（5 项）、`SecurityFiltersTest`（13 项，已从 testExcludes 恢复）全绿 |
| P0-3 生产密钥占位符 | **已修** | `SecretPlaceholders`、`StartupSecretValidator`（common/web）、网关 `SecurityConfigurationValidator`、`deploy/k8s/*` 16 个清单、`docker-compose.yml` | `StartupSecretValidatorTest`（11 项）、网关 `SecurityConfigurationValidatorTest`（14 项）全绿 |

模块测试：`platform/common/web` 127 项、`platform/gateway` 84 项、`platform/auth` 43 项、
`ecom/order-center/service` 14 项 —— **合计 268 项全绿**（`mvn test`，一次反应堆执行）。
全反应堆 `mvn test-compile` 通过；另对 `platform/system`、`audit`、`inventory`、`tenant`、`notify`、
`scm/order`、`ecom/payment`、`platform/product` 单独做了 `test-compile` 回归，均通过。

> **顺带修掉一个 CI flaky**：`SqlInjectionFilterReDoSTest` 用「绝对 200ms 阈值」判 ReDoS 回归，
> 在一次并发跑四模块的反应堆里以 312ms 失败（隔离运行通过）。绝对毫秒预算在共享 runner 上
> 必然抖动，且无法区分「被截断后线性」与「真的超线性」。已改为**按输入规模标定**：同一 payload
> 5k 与 20k 两次计时，允许 8 倍（下限 50ms）——真实二次复杂度会是约 16 倍。断言强度不降，抖动消失。

P0-1 具体口径变化：

- `payOrder(orderNo, paymentNo)` 已删除；替代方法 `applyVerifiedPayment(orderNo, paymentNo,
  paidAmount, transactionId)` 要求实付金额与订单 `payableAmount` 相等、渠道交易号非空。
- `POST /api/v1/orders/{orderNo}/pay` 进入 `internalSignaturePaths`（只认服务间 HMAC），并在
  `SecurityConfig` permitAll（否则无用户 token 的服务调用会在 Spring Security 处 401，永远到不了签名过滤器）；
  `OrderCenterPaymentContractTest` 断言它**不在** `exemptPaths`。
- 权限种子 `019_api_permission_coverage.sql` 移除该路由的权限登记（私有通道不登记权限目录）。
- **未完成**：ecom-payment 验签成功后尚未回调该端点，因此订单状态目前不会被自动推进。这是
  P1-1「订单单一真值源」的后续工作，已在 `AGENTS.md` 中标注为未闭环。

P0-2 具体口径变化：权限服务不可用时，**需要权限的路由**返回 `503 PERMISSION_SERVICE_UNAVAILABLE`
（不再静默降级为「仅角色」）；无需权限的路由（白名单/豁免/已验签）继续可用，运维仍能在
platform-system 故障期登出。`ApiAccessControlFilter` 复用 `JwtAuthenticationFilter` 已解析的权限集合，
把每请求 2 次用户权限查询降为 1 次。旧 token 的 `permissions` 声明仍可用但不标记为已解析，
端点级校验会再做一次权威查询，撤销在下一次请求生效。

P0-3 具体口径变化：`kubernetes`/`k8s` profile 现在按生产类处理（此前不识别，k8s 部署会跳过全部严格校验）；
k8s 清单与 compose 的 ecom 服务补上 `JWT_SECRET`；`deploy/k8s/secrets.yml` 的占位值现在会让服务
**拒绝启动**（这正是该文件「必须替换」的意图）；`cognora.security.secrets.strict` 可显式收紧/放宽。

**追查 P0-3 时另外发现并修掉的三个真实部署缺陷**（都与「密钥能否真正注入」直接相关）：

1. **Helm 完全没有 JWT 密钥**：`deploy/helm/scm-platform/templates/secrets.yaml` 只渲染了
   `DB_USERNAME`/`DB_PASSWORD`，`values*.yaml` 里也没有 jwt 字段。用 Helm 部署时所有服务既拿不到
   `jwt.secret`、也拿不到服务间签名密钥。已补 `jwt-secret` 与 `api-secret-internal-service`，
   并用 `required` 强制提供（缺则在**渲染期**失败，比 Pod 起来后崩溃更早更清楚）。
2. **Helm 默认值被二次 base64**：原模板写 `default "YWRtaW4=" | b64enc`，而 `YWRtaW4=` 本身
   已是 `admin` 的 base64。再编码一次得到 `WVdSdGFXND0=`，解码后是**字面量 `YWRtaW4=`** ——
   数据库用户名/密码是错的。已改为对明文做 `b64enc`。
3. **`scm-platform` 命名空间的 Secret 缺 `api-secret-internal-service`**：该 namespace 的
   17 个清单都引用这个 key，但 Secret 里没有定义，Pod 会以
   `CreateContainerConfigError: secret "scm-secrets" not found` / key-not-found 起不来。
   已在 `platform-auth.yaml` 内联的 `scm-secrets` 中补上。
   注意仓库里有**两个同名 Secret 分属两个 namespace**（`scm-platform` 小写连字符 key、
   `scm-prod` 大写下划线 key），改任意一侧都要核对引用方，已用脚本逐条交叉校验通过。

> 仍未完成（沿用本文档 P1/P2）：每请求 P99 增量为**待实测**（无平台全链路压测数据），
> 端到端「system 宕机演练」「权限撤销时延」仍是单元级证据。

---

## 0.2 阶段二修复记录（2026-11-14，P1-1 / P1-2 / P1-3 / P1-5 / P2-1 / P2-3）

阶段一之后继续推进「阶段二：保交付质量」。逐条状态见 §2 的 P1/P2 小节，这里只列**结论与验证**。

| 条目 | 状态 | 关键落点 | 验证 |
|---|---|---|---|
| P1-1 支付→订单闭环 | 🟡 部分（链路通，真值源未收敛） | `PaymentPaidEvent` / `PaymentPaidListener`（AFTER_COMMIT）/ `SignedOrderPaidNotifier` | `PaymentOrderServiceImplTest`（17）+ `OrderPaidNotificationContractTest`（2）全绿 |
| P1-2 覆盖率门禁 + 上下文冒烟 + 首次提覆盖 | ✅ | `coverage-gate` job、`coverage-baseline.tsv`、`@ContextSmokeTest`、`context-smoke` job | 4 模块实测基线已固化；冒烟反向验证（删 `@Service` 即红） |
| P1-3 盘点冻结保护 | ✅ | `InvStocktakeCommandService.post` 按落账时点重算 | `InvStocktakePostRecalculationTest`（6）全绿 |
| P1-4 前端遗留页 | ⛔ 未处理 | 跨仓库，需单独提交 | — |
| P1-5 端口碰撞 | ✅ | message→8215、schema-registry→18081、Grafana→13000、alertmanager→9094 | `check_port_conflicts.py` 接入 CI，反向验证过 |
| P2-1 ecom 不发布 | 🟡 部分（镜像纳入矩阵；定位冲突未决） | `docker-build` 矩阵 +8 个 ecom 服务 | Dockerfile 与 jar 名已核对 |
| P2-2 性能数字 | ⛔ 未处理 | 需真实压测环境 | — |
| P2-3 文档漂移 | ✅（并纠正原判定） | `docs/design/ADR.md` 顶部现状对照表 | 逐条对照代码核实 |

**顺带修掉的 3 个真实缺陷**（都是做上面条目时撞出来的，不是原清单里的）：

1. **`StatusValidator` 让每个服务启动都依赖注册中心**：`@Component` + 无条件 `@DubboReference` 字段，
   缺 Nacos 时 Dubbo 直接让整个 Spring 上下文启动失败。改为 `ObjectProvider` 懒解析——启动不再
   依赖注册中心，只有真正调用状态流转时才需要。这正是「CI 从不启动上下文」长期掩盖的缺陷类型。
2. **Helm 完全没有 JWT 密钥**（阶段一记录里已列，此处重申因为它属于同一类"配置写了一半"）。
3. **`SqlInjectionFilterReDoSTest` 的绝对 200ms 阈值**在并发反应堆里误报（312ms），改为按输入
   规模标定（5k vs 20k，允许 8 倍），断言强度不降而抖动消失。

**回归结果**：`common/web` 148 + `gateway` 84 + `auth` 43 + `ecom-payment` 56 + `ecom-order-center` 16
+ `inventory` 251 = **598 项，全部通过**（其中 common/web 由 127 增至 148，新增的 21 项见 P1-2）。

> 仍未完成：P2-2（压测）、P0-2 的 P99 与宕机演练（需真实环境）、以及覆盖率数字本身
> （只建了棘轮，未提升）。

---

## 0.3 阶段三修复记录（2026-11-14，收尾剩余项）

| 条目 | 状态 | 落点 | 验证 |
|---|---|---|---|
| P1-4 前端遗留页 | ✅ 已删 | `cognora-aura-web`：删除 `src/app/users/`、`src/app/roles/` | typecheck ✅ / lint 0 error / **318 项测试全绿** / build ✅，产物中已无 `/users`、`/roles` |
| P2-1 k8s ecom 清单 | ✅ 已生成 | `deploy/k8s/ecom-*.yaml`（8 个）+ `scripts/gen_ecom_k8s_manifests.py` | YAML 解析 ✅ / 引用的 ConfigMap 与 Secret key 全部存在 ✅ / `--check` 接入 CI，两种漂移反向验证 ✅ |
| P2-1 定位冲突 | ⛔ 待产品裁决 | `docs/product/POSITIONING_DECISION.md`（冲突、三选项、代价、裁决前约束） | 文档，非代码 |
| P2-2 性能数字 | ⛔ 未产出实测 | `PERFORMANCE_BENCHMARK.md` 顶部加「全是目标值」警示 | 需真实压测环境 |

**顺带修掉的缺陷**：ConfigMap 缺 `clickhouse-host` —— `ecom-analytics` 的 `application.yml`
默认 `${CH_HOST:localhost}:8123`，在 k8s 里会指向 pod 内不存在的端口。新清单会引用这个 key，
生成前先补进了 `deploy/k8s/platform-gateway.yaml` 的 `scm-config`。

**为什么 ecom 清单用生成器而不是手写**：8 个文件近 90 行、结构几乎相同，手写必然漂移
（库名写错、漏一个 secret，都要等 pod 起不来才发现）。生成器把这些事实从各服务
`application.yml` 派生并交叉校验，任一不一致就拒绝生成——`--check` 因此能当 CI 守卫用。

**回归结果**：全反应堆 `test-compile` 通过；`check_port_conflicts.py` 0 冲突；
8 个 ecom 清单 up to date；前端 `cognora-aura-web` 318 项测试 + 生产构建通过。

> 仍未完成：**P2-2 实测数据**（需与生产同规格的压测环境）、**P0-2 的 P99 与宕机演练**
> （需真实环境）、**P2-1 定位裁决**（需产品负责人）、**覆盖率数字**（只建棘轮未提升）。
> 这四项都不是"再写点代码"能解决的，已在各条目里写明所需条件。

---

## 1. 已确认的优势（有据）

| 能力 | 证据 | 置信度 |
|---|---|---|
| 服务间 HMAC 签名 + 5 分钟时钟窗，空密钥 fail-closed | `platform/common/web/.../rest/signature/InternalSignatureVerifier.java:35` | 高 |
| 权限查询 fail-closed，未登记端点默认 DENY | `platform/common/web/.../access/FeignPermissionAccess.java:47`；`.../security/config/ApiAccessControlProperties.java:136` | 高 |
| 多租户 SQL 隔离（JOIN 只过滤驱动表、派生表跳过、INSERT 交 MetaObjectHandler），有测试锁定 | `platform/common/core/.../tenant/TenantInterceptor.java:159`；`TenantInterceptorTest.java`（16 项通过） | 高 |
| Dubbo 跨服务透传租户上下文 | `platform/common/web/.../dubbo/TenantContextFilter.java:39` | 高 |
| 支付回调验签后才置 paid（唯一可信路径） | `ecom/payment/.../controller/PaymentController.java:98`→`handleVerifiedPaymentCallback` | 高 |
| 构建门禁真实生效（Flyway starter 强制、租户列 CI 校验、OpenAPI 漂移校验） | `.github/workflows/maven-build.yml:68`；父 POM antrun 检查 | 高 |
| 库存热点走版本守卫 UPDATE + FEFO + 移动均价 + outbox | `platform/inventory/.../service/command/InvInventoryCommandService.java:58` | 高 |

---

## 2. 问题清单（按优先级）

### P0 — 上线 / 接入真实租户前必须处理

#### P0-1 电商订单可「无支付核销」直接置为已支付
- **状态**：✅ 已修复（见 0.1）；回调闭环待 P1-1 收尾。
- **证据**：`ecom/order-center/service/.../service/impl/OrderCenterServiceImpl.java:138` — 原 `payOrder(orderNo, paymentNo)` 直接 `status=2`、`paidAmount=payableAmount`，**不验签、不关联支付回调、无 owner 校验、无 `@PreAuthorize`**。入口 `.../controller/OrderCenterController.java:69` 仅接收前端传入的 `paymentNo`。
- **冲突**：与平台 documented 铁律「唯一置 paid 的路径是 ecom-payment 验签回调」直接矛盾。
- **影响**：钱未到账即可标记已付并发货。**可能性中、影响高、置信度高**。
- **验收标准**：删除该端点，或改为只由验签回调驱动；补「未验证支付不能置 paid」的断言测试。网关权限目录可能拦未登记端点，但不能替代业务级校验。
- **已落地**：`payOrder(orderNo, paymentNo)` 删除，改为 `applyVerifiedPayment(orderNo, paymentNo, paidAmount, transactionId)`：实付金额必须等于订单 `payableAmount`、渠道交易号必填；路由改为服务间 HMAC 专属（`internalSignaturePaths` + `SecurityConfig` permitAll），浏览器/任意登录用户无法再调用；`OrderCenterPaymentContractTest` 断言不存在「两个字符串就能置 paid」的方法且该路由未被放进 `exemptPaths`。

#### P0-2 在研「短 Token + 每请求在线查权限」把认证正确性绑死到 platform-system
- **状态**：✅ 已修复（见 0.1）；P99 增量仍待实测。
- **证据**：未提交改动从 JWT 移除权限清单（`platform/common/web/.../security/util/JwtUtils.java` buildClaims），改由 `.../security/filter/JwtAuthenticationFilter.java:164` 每请求向 system 查权限。
- **风险**：① 每个业务请求新增一次跨服务权限查询，冷链路多一跳；② 查权限异常时 filter 内 `return Set.of()` 静默降级为「仅角色」，与 `ApiAccessControlFilter` 的 fail-closed 语义不完全一致；③ 依赖 `userPermissions` 缓存整体 `clear()` 失效（`platform/system/.../cache/UserAuthorizationCacheEvictor.java`）。动机（26KB token 超浏览器单 cookie ~4KB 上限）本身合理。
- **影响**：system 抖动可能引发大面积 403；权限撤销生效时延不确定。**可能性中、影响高、置信度高（代码），运行态待实测**。
- **验收标准**：集成测试覆盖「权限撤销→生效时延」「system 宕机时行为」「每请求 P99 增量」；明确「查不到权限=拒绝还是放行」并写进契约。
- **已落地**：语义明确为 fail-closed 并写进 `docs/security/PERMISSION_RESOLUTION_CONTRACT.md` —— 需要权限的路由在权限服务不可用时返回 `503 PERMISSION_SERVICE_UNAVAILABLE`（不再静默降级），无需权限的路由（登出/自助/已验签通道）继续可用；`ApiAccessControlFilter` 复用 JWT 过滤器已解析的权限集合，把每请求 2 次用户权限查询降为 1 次（`security.access.permissions.reused`）；旧 token 的 `permissions` 声明可用但不标记为已解析，保证撤销在下一次请求生效。测试：`JwtAuthenticationFilterPermissionResolutionTest`、`SecurityFiltersTest`（已恢复进构建）、`PermissionServiceFailureFilterTest`。
- **仍待实测**：每请求 P99 增量、端到端 system 宕机演练 —— 需平台全链路压测，当前只有单元级证据。

#### P0-3 生产密钥仍是占位符
- **状态**：✅ 已修复（见 0.1）。
- **证据**：`deploy/k8s/secrets.yml:15` `JWT_SECRET` 为 `CHANGE-ME-` base64；README/AGENTS 确认 Helm/compose 默认 `changeme`。audit 写通道有启动校验器拒绝占位符，JWT 无等价守卫。
- **影响**：非本地部署若忘记注入，JWT 用公开常量签发。**可能性中、影响高、置信度高**。
- **验收标准**：非本地环境启动即校验 JWT / 内部签名密钥非占位且长度达标，否则拒绝启动。
- **已落地**：新增 `SecretPlaceholders`（统一占位符清单）+ `StartupSecretValidator`（common/web，所有扫描 `com.cognora.platform.common` 的服务生效），网关侧在 `SecurityConfigurationValidator` 内做同样校验（网关只扫自己的包）。生产类 profile 现包含 **`kubernetes`/`k8s`**（此前不识别，k8s 部署会跳过全部严格校验）。k8s 16 个单文件清单与 compose 的 8 个 ecom 服务补齐 `JWT_SECRET`；`deploy/k8s/secrets.yml` 的占位值现在会让服务拒绝启动（文件本身要求替换后使用）。`cognora.security.secrets.strict` 可显式收紧/放宽。

### P1 — 90 天内

#### P1-1 「订单」两套系统并存，路径与库重叠
- **状态**：🟡 部分修复 —— 支付→订单链路已闭环；真值源归属仍未收敛。
- **证据**：`scm-order`（POST `/api/v1/orders/query`，富枚举状态机）与 `ecom-order-center`（GET `/api/v1/orders/page`，简单 int 状态机）**同声明 `/api/v1/orders`**；order-center/mall 共享 `db_order`（AGENTS 记录）。
- **影响**：订单真值源不清、同库双写状态风险。**置信度高**。
- **验收标准**：明确「B2B 供应链订单 vs B2C 商城订单」归属与网关前缀隔离文档 + 契约测试。
- **已落地**：P0-1 留下的空档已补 —— `ecom-payment` 在验签成功、支付单落库并**事务提交后**（`@TransactionalEventListener(AFTER_COMMIT)`）经服务间 HMAC 通道回调 order-center 的 `POST /api/v1/orders/{orderNo}/pay`，把订单推进到已支付。事件用 `PaymentPaidEvent` 承载 `orderNo/paymentNo/paidAmount/transactionId`；回调失败只记 `ORDER_PAID_NOT_DELIVERED` 与 ERROR 日志、不打断支付（钱已到账），由对账补偿。`OrderPaidNotificationContractTest` 从支付侧、`OrderCenterPaymentContractTest` 从订单侧双向锁死该路由必须是签名通道。
- **仍未做**：两个服务的路径前缀仍在同一命名空间（`/api/v1/orders` 各服务都声明，靠网关的服务名前缀区分），数据库仍共享 `db_order`；「谁是订单真值源」的文字裁定与网关前缀隔离未做。

#### P1-2 覆盖率门禁形同虚设，且无 CI 启动 Spring 上下文
- **状态**：✅ 已修复（采用棘轮而非一刀切）。
- **证据**：父 POM `com.scm.parent/pom.xml:33` `jacoco.skip=true`；CI push 用 `mvn verify` 未加 `-Djacoco.skip=false`（`.github/workflows/maven-build.yml:135`）；无任何 CI job 启动 Spring 上下文。
- **影响**：70%/60% 门禁从不执行；Bean 装配缺陷静默上线。**置信度高**。
- **验收标准**：至少一条 CI job 跑 `-Djacoco.skip=false` + 关键服务 `@SpringBootTest` 冒烟。
- **已落地**：
  - 门禁参数化（`jacoco.line.minimum`/`jacoco.branch.minimum`），新增 `coverage-gate` job 按 `scripts/ci/coverage-baseline.tsv` 逐模块执行，**只允许上升**（棘轮）。实测水位：gateway 71.9%/55.0%、payment 62.4%/57.6%、order-center 44.7%/44.1%、common-web 28.8%/26.1% —— 一刀切 70/60 会让构建长期变红，而长期红的门禁又会被关掉。详见 `docs/development/COVERAGE_GATE.md`。
  - 新增 `@ContextSmokeTest`（`platform/common/core`）：真 `@SpringBootApplication` + H2 内存库，不连 Nacos/Redis/Kafka/RabbitMQ/Dubbo；新增 `context-smoke` CI job，模块清单在 `scripts/ci/context-smoke-modules.txt`。已覆盖 payment 与 order-center。**反向验证过**：注释掉 `PaymentOrderServiceImpl` 的 `@Service` 会立刻失败并指明缺少 `IPaymentOrderService`。
  - 过程中顺带修掉一个真实缺陷：`StatusValidator` 原先是 `@Component` + 无条件 `@DubboReference` 字段，使**每个服务启动都硬性依赖注册中心可达**；改为 `ObjectProvider` 懒解析后，启动不再依赖注册中心，只有真正调用状态流转时才需要。
- **覆盖率已开始真正提升**（不只是建机制）：`platform-common-web` 由 27.3%/23.6% → **28.8%/26.1%**，
  新增 `JwtUtilsSecurityBoundaryTest`（21 项），覆盖密钥强度守卫（null/空/63 字节/UTF-8 字节序）、
  验签失败（篡改、换密钥重放）、指纹缺失、设备不匹配、严格 IP 开关、刷新令牌冒充访问令牌、
  租户绑定三态（VALID / CLAIM_MISSING / MISMATCH，且不做无租户降级）。
  `JwtUtils` 自身 37% → 52%。基线与 `COVERAGE_GATE.md` 已同步上调。
- **仍未做**：其余模块覆盖率未动；`AuthorizationServerConfig`（0%/174 行）、
  `DynamicPermissionLoader`（0%/131 行）、`SessionManager`（1%/120 行）等大类仍未测，
  排序与建议已写进 `docs/development/COVERAGE_GATE.md` §4。其余 30+ 模块未纳入门禁与冒烟。

#### P1-3 库存盘点落账无「冻结期」保护
- **状态**：✅ 已修复。
- **证据**：在研 `platform/inventory/.../service/command/InvStocktakeCommandService.java:234` — 创建时冻结账面数，落账按 `diff=counted-book` 逐条 `adjustInventory`。审批期间若该 SKU 出入库，落账会把差异叠加到已变化的台账。仅有 `StocktakeStatusTest`（状态机），无并发/漂移测试。
- **影响**：账实偏差。**可能性中、影响中、置信度高**。
- **验收标准**：补「盘点期间发生出入库」的落账测试；明确锁定库位还是按落账时点重算差异。
- **已落地**：选择**按落账时点重算**（而非锁定库位——锁定会让审批期无法正常发货）。落账时以实盘数为锚、用当前账面重算差异：`recalculated = countedQuantity - currentAvailable`，与单据记录的 `diffQuantity` 不一致即视为漂移，记 WARN、把漂移量写进明细备注、并按重算值调整。`InvStocktakePostRecalculationTest`（6 项）覆盖：审批期出库后重算、无变动、重算为 0 不产生空流水、漂移落备注、未盘点跳过、多明细各自重算。

#### P1-4 前端遗留重复页面
- **状态**：✅ 已修复（在 `cognora-aura-web` 仓库内完成）。
- **证据**：`cognora-aura-web/src/app/users/page.tsx:20` 是无 locale 前缀、Ant Design、`users=[]` 不拉数据的旧壳，与已迁移的 `[locale]/(app)/system/user` 并存；`roles/`、`users/[id]` 同样在 locale 结构外。
- **验收标准**：删除 locale 结构外遗留路由，统一走已迁移页面。
- **已落地**：删除 `src/app/users/`（含 `[id]/page.tsx` 与其 `page.test.tsx`）与 `src/app/roles/`。删除前确认：① 已迁移页 `[locale]/(app)/system/{user,role}` 存在；② 侧边栏 `menu-config.tsx` 未链接到旧路由；③ 旧页只直连 `client`，无任何页面引用它们 —— 即孤立路由。删除后 **`src/app` 只剩 `[locale]`**，全站路由统一。
- **验证**：`npm run typecheck` 通过、`npm run lint` 0 error（16 个既有 warning）、`npm test` **318 项全绿**、`npm run build` 成功且产物中已无 `/users`、`/roles`。**踩坑记录**：typecheck 首次失败是因为 `.next/types/validator.ts` 里残留了指向已删路由的生成类型；清掉 `.next/types` 即可——它是构建产物，不是源码问题。

#### P1-5 端口 / 共享库碰撞未在配置层根治
- **状态**：✅ 已修复。
- **证据**：message↔approval 同 8209、schema-registry↔system 同 8081、Grafana↔前端同 3000（README 已警示但仍靠人工 override）。
- **验收标准**：改默认端口，让全栈一键起不冲突。
- **已落地**：message → **8215**（approval 保留 8209，两者都加 `${SERVER_PORT:…}` 便于临时覆盖）；compose 中 schema-registry 宿主端口 → **18081**（容器内仍 8081，让给 platform-system）；Grafana → **13000**（容器内仍 3000，让给前端 dev server）；顺带发现并修掉 alertmanager↔Kafka 的宿主机 9093 冲突（alertmanager → **9094**）。新增 `scripts/ci/check_port_conflicts.py` 并接入 CI，**反向验证过**（把 message 改回 8209 会立刻报错）。README 的端口表与「为什么抢端口」问答已同步。

### P2 — 半年内

- **P2-1 产品定位分裂**：PRD 说「中大型电商/零售/制造 SCM」（`docs/product/PRD.md:16`），架构评审文档说「B2B2C marketplace」，生态规划说「3 年全电商中台」。三份愿景不一致，导致 ecom 已铺 8 个服务但 CI Docker 矩阵只构建 15 个、**一个 ecom 都不发布**（`.github/workflows/maven-build.yml:299`），k8s 无 ecom manifest。属「代码已写、交付未通」。
  - **工程部分已补齐**：① 8 个 ecom 服务全部进入 `docker-build` 矩阵（现 23 个镜像）；② 8 个 k8s 清单已生成（`deploy/k8s/ecom-*.yaml`），**但不是手写的** —— 由 `scripts/gen_ecom_k8s_manifests.py` 从各服务 `application.yml` 派生端口与库名，`--check` 进 CI 防漂移，并**反向验证过**两种漂移（改坏清单、改错表）都会失败。过程中发现并补上 ConfigMap 缺失的 `clickhouse-host`（`ecom-analytics` 原本会指到 pod 内不存在的 localhost:8123）。
  - **定位冲突本身仍未裁决**：这是产品决策，不是代码改动。已把冲突、三种收敛选项与代价、以及"裁决前工程上必须守的约束"写进 `docs/product/POSITIONING_DECISION.md`，交给产品负责人与架构组拍板。**注意**：新增的 ecom 清单是「照现有模式生成」，尚未经真实集群部署验证，不应当作可上线的证据。
- **P2-2 性能数字是目标非实测**：`docs/project-management/PERFORMANCE_BENCHMARK.md` 里 TPS>10000、QPS>50000 全打 `[x]` 但「当前值」为空，loadtest 脚本仅 20~100 并发。勿对外当已达成承诺。
  - **只加固了"不许误引用"，未产出实测数据**：在该文档顶部加了醒目警示——所有数字都是目标值、「当前值」一列全空、现有压测并发量级与目标差 3 个数量级、不得写进 SLA 或对外材料。**真正的实测需要与生产同规格的压测环境，无法用静态改动替代**，因此该条维持"未完成"。
- **P2-3 文档漂移**：ADR 写 MySQL/Canal/Spring State Machine，实际是 PostgreSQL/Debezium/枚举状态机；QUICK_START 部分过时。建议 ADR 标 superseded。
  - **已修复（并纠正了原判定）**：在 `docs/design/ADR.md` 顶部加了「阅读前必看」的现状对照表。核对代码时发现原评审的表述也不完全准确：仓库里 **Canal 与 Debezium 都没有引入**，`spring-statemachine` 有依赖声明但**代码零引用**，ShardingSphere 无任何依赖。表里按实际代码写，并声明「代码 > AGENTS.md > docs/design/*」的权威顺序。

---

## 3. 下一步发展目标与功能优先级（产品视角）

**核心判断**：平台「技术纵深」明显领先「产品闭环完整度」与「可交付性」。当前不宜继续横向铺电商/AI 新模块，应先**收敛定位、打通一条可上线的垂直闭环**。

**建议定位收敛为**：面向中大型电商/零售的**多租户供应链履约后台（SCM 为主、商城为辅）**，先做到「能上线、能接第一个真实租户」。

| 优先级 | 功能 | 理由 |
|---|---|---|
| P0 | 支付→订单状态可信闭环（修 P0-1） | 交易正确性底线 |
| P0 | 短 Token 权限链路稳定性验证（P0-2） | 否则登录后随时 403 雪崩 |
| P1 | 盘点/安全库存/估值闭环收尾 + 前端对接 | 在研代码已成型，差冻结保护与 UI |
| P1 | 订单单一真值源 + 拆单/退货闭环 | 消除双订单系统歧义 |
| P1 | 租户自助开通（免手工建库/种子） | SaaS 接入关键卡点 |
| P2 | 缺货预警→一键补货采购单 | PRD 核心痛点，价值高 |
| P2 | 商城 B2C 最小闭环（若确定做电商） | 先验证再扩，别一次全铺 |

---

## 4. 架构完善路线（分阶段）

**阶段一（0–30 天）稳交易与认证**
- 修 P0-1 / P0-2 / P0-3
- 认证权限链路补集成测试与 fail 语义契约
- 生产密钥启动守卫

**阶段二（30–90 天）保交付质量**
- CI 开覆盖率门禁 + 关键服务 Spring 上下文冒烟
- 统一订单真值源、隔离 ecom 路由前缀
- 盘点冻结保护；清前端遗留页；根治端口冲突
- 把 ecom 服务纳入或明确移出发布矩阵（消除「写了不发」）

**阶段三（90–180 天）通租户与可观测**
- 租户自助开通 API + 配额计量
- 把 PERFORMANCE_BENCHMARK 目标落成真实压测报告 + SLO/告警
- ADR/文档与代码对齐

---

## 5. 证据索引（关键文件）

- `ecom/order-center/service/src/main/java/com/cognora/ecom/ordercenter/service/impl/OrderCenterServiceImpl.java:138`
- `ecom/order-center/service/src/main/java/com/cognora/ecom/ordercenter/controller/OrderCenterController.java:69`
- `ecom/payment/service/src/main/java/com/cognora/ecom/payment/controller/PaymentController.java:98`
- `platform/common/web/src/main/java/com/cognora/platform/common/security/filter/JwtAuthenticationFilter.java:164`
- `platform/common/web/src/main/java/com/cognora/platform/common/security/filter/ApiAccessControlFilter.java:103`
- `platform/common/web/src/main/java/com/cognora/platform/common/security/config/ApiAccessControlProperties.java:136`
- `platform/common/core/src/main/java/com/cognora/platform/common/tenant/TenantInterceptor.java:159`
- `platform/inventory/service/src/main/java/com/cognora/platform/inventory/service/command/InvStocktakeCommandService.java:234`
- `platform/system/service/src/main/java/com/cognora/platform/system/cache/UserAuthorizationCacheEvictor.java`
- `com.scm.parent/pom.xml:33`
- `.github/workflows/maven-build.yml:68` / `:135` / `:299`
- `deploy/k8s/secrets.yml:15`
- `docs/product/PRD.md:16`
- `docs/project-management/PERFORMANCE_BENCHMARK.md`
- `cognora-aura-web/src/app/users/page.tsx:20`

> 维护说明：本文件为某一时间点的评审快照；后续修复请以代码与 `git log` 为准，并同步更新对应条目的状态。