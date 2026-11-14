# 设计：注解补齐与闲置设施激活（审计日志 / 方法级权限 / 读写分离 / 分区维护自动化）

- 日期：2026-11-12
- 状态：builder check 完成，4 个问题已核实并回填（D3/D8/D9/D10），待用户批准
- 范围：`cognora-aura-platform` 后端（platform / scm / ecom / lms 四个域 reactor）

## 1. 背景与动机

平台库（`platform/common/*`）里已经建好四类能力，但绝大多数服务没有用上：

1. **审计日志**：`@AuditLog` 注解 + `SysAuditLogAspect`（`platform/common/web` 的
   `AuditLogAutoConfiguration` 自动装配，所有依赖 common-web 的服务都生效），经 HMAC
   签名通道投递到 platform-audit 落 `db_audit`。但 24 个服务里只有 4 个打了注解
   （scm-finance 43 处全覆盖、lms-tms 17 处、platform-system 33 处、platform-auth 6 处）。
   经统计（PowerShell 扫描 `*Controller.java`，排除 `.vmcheck`/`enterprise-agent`/前端）：
   **约 380 个写端点（POST/PUT/DELETE/PATCH）没有 @AuditLog**，其中 ecom 八个服务全部为 0。
2. **方法级权限**：`platform/common/web` 的 `SecurityConfig` 已 `@EnableMethodSecurity`，
   `@PreAuthorize("hasAuthority('<code>')")` 全局可用；但只有 platform-system（61 处）
   和 platform-auth（19 处）在用，其余 22 个服务全部为 0。运行时真正管权限的是
   `ApiAccessControlFilter`（URL → 权限码，fail-closed，未注册端点默认 DENY 403
   `API_NOT_REGISTERED`），权限目录在 `db_permission`（seed SQL 共 662 条
   URL+method → code 条目）。
3. **读写分离**：`platform/common/data-rw`（`@Master`/`@Slave` + `ReadWriteRoutingDataSource`
   + 熔断/健康检查/读主窗口）已完整实现且有单测，但只有 platform-product 激活
   （`spring.datasource.rw.enabled=true`）；scm-order 有一份未启用的
   `application-readwrite.yml` 模板。
4. **分区/保留策略**：`scripts/db/partition/create-partitions.sh`、
   `create-audit-partitions.sh`、`drop-old-partitions.sh`、
   `scripts/db/retention/apply-retention.sh` 都存在且幂等，但纯手动执行，没有调度。

用户决策（2026-11-12）：分库分表方向选择**「激活已有设施」**——不引入 ShardingSphere，
把读写分离推广开、把分区/保留脚本挂上自动调度；同时把 @AuditLog 与 @PreAuthorize
两类注解补齐到各服务 controller。

## 2. 目标 / 非目标 / 反目标

### 目标

1. **G1 @AuditLog 补齐**：除豁免端点外，所有业务服务的写端点都带 `@AuditLog`
   （中文 operation + businessType + riskLevel），风格与 scm-finance / lms-tms 现有用法一致。
2. **G2 @PreAuthorize 补齐**：除豁免端点外，业务服务的**全部端点**（含 GET 查询）都带
   `@PreAuthorize`，权限码必须能在权限目录（`scripts/db/seed/*.sql` 的 URL+method→code 行）
   中找到且一致；目录中查不到的端点**不自动注解**，输出清单交人工决策。
3. **G3 读写分离 Phase 1 激活**：对拥有独立库的服务启用 `spring.datasource.rw` 并打
   `@Slave`/`@Master` 注解；从库默认指向主库（零基建变更），未来接真副本只需
   `DB_SLAVE_HOST` 等环境变量。
4. **G4 分区维护自动化**：把四个既有脚本按频率自动调度（docker-compose profile +
   k8s CronJob 两种部署形态都交付）。
5. **G5 护栏**：新增 CI 检查脚本，防止 `@PreAuthorize` 注解码与权限目录漂移。

### 非目标

- 不引入 ShardingSphere / 不做真正的水平分库分表（架构评审 P3 项，另立项目）。
- ecom 八个服务（共享 db_user/db_finance/db_order/db_warehouse）**不做**读写分离
  激活——共享库意味着一个服务的从库路由影响别服务，需要整体设计，本次只排除并记录。
- 不修改审计投递语义（best-effort + HMAC 通道保持现状）、不改 `db_audit` schema。
- 不自动向权限目录补数据行（缺失端点只报告；补数据是独立决策，因为补行使
  `API_NOT_REGISTERED` 403 的端点重新可用，属于权限开通行为）。
- 不动 `platform-gateway` / `platform-message`（无 controller）。
- 不修复 247 个文件的 mojibake；编辑路径上的中文注释若损坏则按 AGENTS.md 处理。

### 反目标（出现即算失败）

- `@PreAuthorize` 码与目录不一致 → 合法用户被 403（缓解：G5 CI 检查 + 映射表来自
  目录单一事实源）。
- 注解打在 HMAC 服务间端点 / 支付回调 / whitelist 端点上 → 破坏服务间调用与回调闭环
  （缓解：豁免清单在 5.2/5.1 明确）。
- 读写分离导致 read-your-writes 违约（缓解：`read-master-after-write` 窗口 + 命令方法
  一律 `@Master`）。
- 分区维护脚本误删数据（缓解：脚本保持幂等、只前滚建分区/按保留期 DROP；保留期参数
  与现状一致：audit 2y、login 1y、api logs 90d、notify 6mo、order events 3y）。

## 3. 约束（来自 AGENTS.md，必须遵守）

- 构建一律 `mvn -f com.scm.parent/pom.xml`；先 `mvn install -f bom/pom.xml`；
  `-pl` 用 `../<module>` 相对路径；新改模块默认过 `mvn clean test-compile test`。
- 不改 `com.cognora.*` 包名与 `cognora.*` 配置前缀；新 YAML 配置键用 `cognora.*` 或
  文档既有键（`spring.datasource.rw.*`、`spring.datasource.dynamic.*`）。
- 服务间端点授权走 `internal-signature-paths`（HMAC），**不得**塞进 `exempt-paths`。
- 不得给分区表加不含分区键的 UNIQUE 约束。
- 中文注释 mojibake 不修；新注释用正常中文或英文。
- CI 会跑 `scripts/ci/check_openapi_drift.py`——本次不改 lms-tms / platform-inventory
  的 request mapping（本次也不应改任何 mapping，只加注解），故无需更新快照。
- 每个改动的服务模块保持既有测试通过；新增工具脚本不进 Java 构建。

## 4. 已定决策

**用户拍板：**
- 分库分表 = 激活已有设施（读写分离推广 + 分区脚本调度），不引入 ShardingSphere。
- @AuditLog 只打写端点；@PreAuthorize 打全部端点（含 GET）——用户对默认口径未提出异议。

**本次由 agent 拍板（用户可在评审时推翻）：**
- D1：目录缺失的端点不自动补 @PreAuthorize，生成报告
  `docs/superpowers/specs/2026-11-12-permission-catalog-gaps.md` 交人工决定是否补 seed 行。
- D2：读写分离 Phase 1 服务清单 = scm-order、scm-supplier、scm-purchase、
  scm-warehouse、lms-tms、platform-inventory（各拥独立库、读写特征明显）；
  platform-product 已激活不动；其余 platform 服务与 ecom 为 Phase 2/3，本次不做。
- D3：激活形态 = **platform-product 已验证的形态**（builder check 后修订，替代原
  「baomidou primary 指向 rw group」的模板形态）：
  `spring.datasource.dynamic.enabled: false`（baomidou starter 关闭）+
  `spring.datasource.rw.enabled: true` + `spring.datasource.rw.default-target: MASTER` +
  `spring.datasource.rw.groups.<group>.{master,slaves}`。依据：platform/product/service
  `application.yml` 是仓库中唯一在跑的先例，其注释明确说明 baomidou-primary 形态会
  「再包一层（需要额外配置 primary）」而弃用；且 scm-order 的 readwrite 模板形态在
  全仓库无任何服务运行。`default-target: MASTER` 同时保证 Flyway 等启动期数据访问
  落主库（product 注释原话）。按各服务起始状态分别处理：
  - scm-order、platform-inventory（现状 plain `spring.datasource.url`）：保留原
    `datasource.url` 块作为 rw 关闭时的回退（product 同款），新增 rw 块；
  - scm-warehouse、scm-supplier、scm-purchase、lms-tms（现状**激活的**
    baomidou `dynamic.primary: master` + `datasource.master.*`）：把
    `datasource.master.*` 的连接信息整体搬进 `rw.groups.<group>.master.*`，
    `dynamic` 块改为 `enabled: false`，避免 `ReadWriteDatasourceConflictChecker`
    因 dynamic 数据源未被 rw groups 覆盖而拒绝启动；
  - 从库 URL 统一 `${DB_SLAVE_HOST:${DB_HOST:localhost}}` +
    `${DB_SLAVE_PORT:${DB_PORT:5432}}`（无真副本时 = 单库，路由机器全开、数据面等价）。
- D8：`signatureOrPermissionPaths`（search index 一类「HMAC 签名**或**权限码」端点，
  `ApiAccessControlProperties.signatureOrPermissionPaths` / `.signatureOrPermissionCode`）
  **不打 @PreAuthorize**——filter 已实现 OR 语义，方法级注解会把无用户上下文的
  HMAC 内部调用 403 掉。这类端点计入豁免清单，G2 目标表述相应为
  「除豁免外全部端点」。
- D9：权限目录的 **templated 段（api_path 含 `{var}` 占位，seed 019 第二段约 349 行）
  视为完全生效**。依据：`ApiPermissionMatcher` 已实现占位符/Ant 匹配，并被
  `platform/system/service/src/test/.../service/Impl/SysPermissionServiceImplMatcherTest`
  与 `.../support/ApiPermissionMatcherTest` 固化；seed 019 头部「需先把
  findPermissionsByUrl 升级为 Ant/占位符匹配后才生效」的注释已过时，本次在触碰
  该文件时同步修正该注释（事实性修正，非 mojibake 修复）。
- D10：分区/保留脚本的调度容器 env 一律用脚本实际读取的变量名
  （builder check 修订）：`DB_HOST`/`DB_PORT`/`DB_USER`/`DB_PASSWORD`
  （脚本内 `psql -h "$DB_HOST"` 显式传参，`PGHOST` 无效）；
  `apply-retention.sh` 另读 `CH_HOST`/`CH_PORT`/`CH_USERNAME`/`CH_PASSWORD`，
  其 ClickHouse 段自带连通性守卫（`ch_query "SELECT 1"` 失败即整段跳过），
  调度容器直接配 `CH_HOST=clickhouse`、`CH_PORT=8123` 指向 compose 既有服务。
- D4：businessType 前缀表（§5.1）与 riskLevel 刻度（§5.1）。
- D5：分区调度交付两种形态：`docker-compose.yml` 新增 `maintenance` profile 的
  `db-maintenance` 服务（postgres:18 镜像 + 挂载 `scripts/db` + 循环调度）；
  `deploy/k8s/db-maintenance-cronjob.yml` CronJob。两者都只调用既有脚本。
- D6：CI 护栏脚本 `scripts/ci/check_permission_annotation_drift.py`（模式照抄
  `check_openapi_drift.py`）：解析全部 controller 的 `@PreAuthorize` 与 seed SQL
  目录行做双向一致性检查，PR 即跑。
- D7：`@AuditLog` 的 `recordParams` 保持默认 true；若端点请求体含明文凭证
  （改密/重置类）则 `recordParams = false`（与现状一致，finance 示例未设置即默认）。

## 5. 工作流规格

### 5.1 @AuditLog 扫描（G1）

- 注解：`com.cognora.platform.common.log.annotation.AuditLog`，
  属性 `operation`（中文短句，动词开头，如「创建采购订单」）、`businessType`
  （大写前缀 + 实体，如 `PURCHASE_ORDER`）、`riskLevel`（见刻度）。
- **riskLevel 刻度**（从现有用法归纳）：4 = 不可逆删除、授权/权限变更、密码与凭证、
  资金登记；3 = 业务对象创建/修改/状态流转、导出含敏感数据；2 = 一般导出/批量查询/
  信息上报；1 = 低风险确认、扫描触发。
- **businessType 前缀表**：scm-order→`ORDER`，scm-purchase→`PURCHASE`，
  scm-supplier→`SUPPLIER`，scm-warehouse→`WAREHOUSE`，platform-inventory→`INVENTORY`，
  platform-product→`PRODUCT`，platform-tenant→`TENANT`，platform-approval→`APPROVAL`，
  platform-notify→`NOTIFY`，platform-document→`DOCUMENT`，platform-file→`FILE`，
  ecom-member→`MEMBER`，ecom-promotion→`PROMOTION`，ecom-payment→`PAYMENT`，
  ecom-order-center→`ORDER_CENTER`，ecom-mall→`MALL`，ecom-fulfillment→`FULFILLMENT`，
  ecom-search→`SEARCH`，ecom-analytics→`ANALYTICS`。已有前缀不变（FINANCE/LOGISTICS/USER）。
- **豁免清单（不打注解）**：
  - platform-audit 的 `/sys-audit-log`（审计写通道自身，HMAC 保护，无用户上下文）；
  - 支付渠道回调（ecom-payment 的 Alipay/WeChat 回调路径——渠道验签，无用户 token；
    其服务层 `handleVerifiedPaymentCallback` 可加服务层注解，由 builder 判断落地）；
  - ecom-order-center 的 `POST /api/v1/orders/{orderNo}/pay`（internal-signature 端点；
    seed 019 头部亦明确该路径已从权限目录移除，只走 HMAC 通道）；
  - `signatureOrPermissionPaths` 端点（D8，如 `/api/v1/search/index/**`）；
  - 各服务 `whitelist`/`exempt-paths` 中的端点（actuator、api-docs 等）。
  **豁免的事实源**（builder check 修订）：`platform/common/web` 的
  `ApiAccessControlProperties` 默认值（`whitelist`/`exemptPaths`/`exemptPathExclusions`/
  `internalSignaturePaths`/`signatureOrPermissionPaths`）加上各环境经 Nacos 的覆盖；
  仓库内各服务的 application.yml 并没有覆盖这些列表。builder 逐端点对照该类默认值
  后再落注解。
- GET/查询端点不打（与 finance/tms 现状一致）。
- 完成后更新 §7 的 before/after 统计表。

### 5.2 @PreAuthorize 扫描（G2）

- 注解：`org.springframework.security.access.prepost.PreAuthorize`，
  默认形态 `@PreAuthorize("hasAuthority('<code>')")`（与 platform-system 现状一致）。
  **例外（C1 修正）**：请求 URL 在运行时可能命中多个目录码时（字面量行 + `{var}` 模板行
  同时匹配，见下），必须用 `hasAnyAuthority('<primary>', '<other>', ...)` 覆盖全部候选；
  此时「目录行本身表达多码」正是本节允许 `hasAnyAuthority` 的条件。`hasRole` 仍然不用。
- **码的唯一事实源**：`scripts/db/seed/*.sql` 中形如
  `('<code>', '<名称>', '<menu>', '<url>', '<METHOD>', <sort>, <risk>)` 的目录行
  （`019_api_permission_coverage.sql` 等）。映射规则（builder check 修订）：
  - 控制器类级 `@RequestMapping` + 方法级 mapping 拼出完整路径；
  - literal 行按 api_path 精确匹配；templated 行（`{var}` 占位）同样有效（D9），
    `{id}` ↔ `{id}` 原样匹配，个别 `*` 通配先归一化再匹配；
  - method 精确匹配（templated 行个别方法为 `*` 的，按任意方法匹配）；
  - filter 端语义是 **anyMatch**（URL 的全部候选码命中其一即放行）。目录里没有重复
    (url, method) 行（642 个唯一对）**并不等于**每个请求只有一个码：Spring 的
    `AntPathMatcher` 让 `/api/v1/fulfillments/{fulfillmentNo}` 命中
    `/api/v1/fulfillments/page`，因此候选码集合必须按**运行时匹配**计算；
    `hasAnyAuthority` 与 CI 护栏都据此推导（`runtime_candidates`）。
- **豁免清单**：与 5.1 相同（HMAC 端点、渠道回调、whitelist/exempt-paths）。
  另外：`@PreAuthorize` 不得打在服务间（Dubbo/RestClient 内部）调用的非 HTTP 代码上——
  本扫描只处理 controller。
- 目录中**查不到**的端点：不注解，记入 D1 报告（路径 + HTTP 方法 + 所属服务）。
  说明：这类端点今天本就被 `ApiAccessControlFilter` 以 `API_NOT_REGISTERED` 403，
  不注解不改变现状。
- platform-system / platform-auth 已有注解的服务**只查漏**（对照目录校验已有注解的
  码是否仍与目录一致，不一致则修正），不重打。
- G5 护栏：`scripts/ci/check_permission_annotation_drift.py`，
  - 输入：`platform|scm|ecom|lms` 四域 `*Controller.java` + `scripts/db/seed/*.sql`；
  - 校验：每个 `@PreAuthorize` 码在目录中存在且 URL+method 对得上；目录行若无对应
    端点则告警（不阻塞）；`.vmcheck` 等副本目录必须排除（路径过滤模式与
    `check_openapi_drift.py` 相同）；
  - 退出码非 0 时 PR 失败；首次运行须全绿后再挂入
    `.github/workflows/maven-build.yml` 的 build job。

### 5.3 读写分离 Phase 1 激活（G3）

- 配置形态按 **D3**（platform-product 已验证形态）：`dynamic.enabled: false` +
  `rw.enabled: true` + `rw.default-target: MASTER` + `rw.groups.<group>`。
  group 名 = 该服务库名对应的业务名（`order`/`warehouse`/`supplier`/`purchase`/
  `logistics`/`inventory`，与 product 的 `groups.product` 一致——一服务一库一组）。
  - scm-warehouse/supplier/purchase、lms-tms：把现有 `datasource.master.*` 搬进
    `rw.groups.<group>.master.*`，`dynamic` 改 `enabled: false`（见 D3，冲突 checker）；
  - scm-order、platform-inventory：保留 plain `datasource.url` 回退块 + 新增 rw 块
    （platform/product 的 application.yml 同款结构）；
  - master/slave 的 URL、凭据沿用各服务现有 `${DB_*}` 环境变量；slave 用
    `${DB_SLAVE_HOST:${DB_HOST:localhost}}` 落回主库；
  - 保留模板中的 `read-master-after-write: 2s`、`replication-lag-tolerance: 1s`、
    `load-balance: ROUND_ROBIN`、health-check 配置；
  - `slaves` 列表保留 1 个条目（slave1），名字固定，未来只换 env。
- 注解规则（`@Slave` = `com.cognora.platform.common.data.rw.annotation.Slave`，
  `@Master` 同包）：
  - 有 CQRS 分层的服务（`service/query/**`、`service/command/**`）：query 类整体
    `@Slave`（类级），command 类整体 `@Master`（类级）；
  - 无分层的服务：查询方法（`select/find/get/query/list/page/count/stat*`）`@Slave`，
    写方法（`save/create/update/delete/insert/deduct/lock/apply*/transition*`）`@Master`，
    语义不明的方法**默认不打**（不打 = 走 default-target MASTER，安全）；
  - 同一事务内混合读写一律不打 `@Slave`（命令方法所在事务整体走 master）；
  - **platform-inventory 的 command 服务已有 @Master/@Slave 注解**（builder check
    核实）——该服务先盘点现状、只补缺，不重打。
- `.env.example` 增加 `DB_SLAVE_HOST`/`DB_SLAVE_PORT`/`DB_SLAVE_USERNAME`/
  `DB_SLAVE_PASSWORD`（注释说明留空 = 指向主库）；k8s manifest 的各 Phase 1 服务
  deployment 增加 env 占位（默认空）。docker-compose 的 ecom 部分已有
  `DB_SLAVE1_HOST`，不改（ecom 不在本期）。
- 观察点（写进各服务验证清单）：启动日志 `[RW-Config] Group [x] configured`、
  `/actuator/health` 含 RW 健康项、`rw_*` 指标出现。数据面行为在从库=主库阶段
  与现状等价，灰度风险集中在配置错误导致启动失败（`ReadWriteDatasourceConflictChecker`
  会拦截 dynamic 与 rw 冲突配置——按 checker 的规则改，报错即回退该服务）。
- 平台服务 Phase 1 中 platform-inventory 特殊注意：AGENTS.md 提到
  `platform-inventory-service`/`platform-audit-service` 用 `<classifier>exec</classifier>`，
  只改 yml 不动 pom/构建。

### 5.4 分区维护自动化（G4）

- 只调用既有脚本：`create-partitions.sh`、`create-audit-partitions.sh`（月度）；
  `drop-old-partitions.sh`、`apply-retention.sh`（周度）。脚本必须保持幂等；若
  builder 发现脚本有非幂等路径，允许最小修补（脚本内，不改语义）。
- **compose 形态**（D5a）：`docker-compose.yml` 增加 `db-maintenance` 服务，
  `profiles: [maintenance]`，镜像 `postgres:18`，挂载 `./scripts/db:/opt/db:ro`，
  环境变量（D10，用脚本实际读取的名字）：
  `DB_HOST=postgres`、`DB_PORT=5432`、`DB_USER=${POSTGRES_USER:-admin}`、
  `DB_PASSWORD=${POSTGRES_PASSWORD:-admin123}`、
  `CH_HOST=clickhouse`、`CH_PORT=8123`、`CH_USERNAME`/`CH_PASSWORD`（compose
  既有 clickhouse 凭据 env），入口为仓库新增小脚本
  `deploy/maintenance/run-maintenance.sh`：无限循环，月度任务每 30d 执行一次，
  周度任务每 7d 执行一次（首跑立即执行），全部输出 stdout（docker logs 可见）。
  脚本只 `bash` 调用既有 .sh，不在新脚本里内联 SQL；ClickHouse 保留段由脚本自身
  的连通性守卫决定是否执行（CH 不可达时跳过且不算失败）。
- **k8s 形态**（D5b）：`deploy/k8s/db-maintenance-cronjob.yml`，两个 CronJob
  （monthly/weekly），job 镜像 `postgres:18`，`command` 调用同一 run-maintenance.sh
  的单次模式（`run-maintenance.sh once <task>`），env 同上并引用既有 secrets
  （`secrets.yml` 的数据库凭证），`restartPolicy: OnFailure`，并发策略
  `Forbid`。参照 `deploy/k8s/configmap.yml`/`_helpers.tpl` 的既有风格。
- 文档：`docs/operations/OPERATIONS_MANUAL.md` 或 runbook 增补一节
  「分区与保留策略自动调度」（中文），说明两种形态、验证命令、人工覆盖方式
  （手动跑脚本的既有命令保留有效）。

### 5.5 验证与交付（G5 + 全局）

- 每个 Java 模块：`mvn clean test-compile test -pl ../<module> -f com.scm.parent/pom.xml`。
- 注解统计表（§7）更新：服务 × {写端点数, @AuditLog 数}、
  {端点数, @PreAuthorize 数}，前后对比。
- `check_permission_annotation_drift.py` 首次全绿。
- RW 服务逐个本地启动验证（infra 起来的前提下）或至少上下文测试；数据面等价性
  由「从库=主库」默认值保证。
- `db-maintenance` 手动触发一轮（compose：`docker-compose --profile maintenance run
  --rm db-maintenance` 或直接跑 run-maintenance.sh once），核对分区未新增重复、
  DROP 未越保留期。

## 6. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| @PreAuthorize 码与目录漂移 | 合法用户 403 | 码仅从目录推导 + CI drift 检查 |
| 豁免端点误注解 | 服务间调用/支付回调断 | 豁免清单 + 逐服务核对 api-filter 配置 |
| 目录缺口端点（403 现状）被误以为本次引入 | 用户误解 | D1 报告说明这些端点今天已被 filter 403 |
| RW 配置冲突启动失败 | 服务不可用 | 冲突 checker 报错即回退该服务；先 scm-order 单服务试运行再铺开 |
| 维护脚本误删 | 数据丢失 | 保留期参数与现状一致；脚本幂等；首跑在低峰人工盯日志 |
| 注解数量大，review 噪音 | 合并困难 | 按服务分批提交；注解-only 变更与 yml 变更分开提交 |

## 7. 现状统计与交付顺序

### before（2026-11-12 实测，PowerShell 扫描）

| 域/服务 | controllers | @AuditLog | 写端点 | @PreAuthorize |
|---|---|---|---|---|
| ecom×8 | 19 | 0 | 67 | 0 |
| lms-tms | 8 | 17 | 29 | 0 |
| platform×10（有 controller 的） | 54 | 39 | 196 | 80 |
| scm×5 | 47 | 43 | 191 | 0 |
| 合计 | 128 | 99 | 483 | 80 |

（platform 内：system 33+61、auth 6+19，其余 8 个服务全 0；scm 内：finance 43，其余 4 服务全 0。）

### after（2026-11-13 实测，基线 `07d4edd0` → `4b050a56`）

| 域/服务 | controllers | @AuditLog | 写端点 | @PreAuthorize |
|---|---|---|---|---|
| ecom×8 | 19 | 34 | 67 | 61 |
| lms-tms | 8 | 29 | 29 | 36 |
| platform×10（有 controller 的） | 50 | 153 | 174 | 265 |
| scm×5 | 47 | 188 | 191 | 311 |
| 合计 | 124 | 404 | 461 | 673 |

新增量：**@AuditLog +305、@PreAuthorize +593**。

口径说明：注解两列 before/after 同口径（`git grep` 统计 git 跟踪的 `*Controller.java`，
before 取自基线 `07d4edd0`，与 before 表的 99 / 80 完全吻合）；controllers 与写端点两列
after 只统计 git 跟踪的 controller（794 个端点、其中 461 个写端点，来源
`docs/superpowers/specs/2026-11-12-permission-mapping.json`），before 表的 128 / 483
是 2026-11-12 的工作区扫描，额外含未跟踪的在建 controller（platform +4 个 controller、
+22 个写端点），因此两列不能逐格相减——注解两列才可以。

**本次新增的 @AuditLog（按服务，共 305 处）**

| 服务 | 新增 | 备注 |
|---|---|---|
| ecom/analytics | 19 | |
| ecom/fulfillment | 7 | |
| ecom/mall | 1 | |
| ecom/order-center | 5 | |
| ecom/payment | 2 | |
| scm/purchase | 67 | |
| scm/supplier | 38 | |
| scm/warehouse | 30 | |
| scm/order | 10 | |
| platform/tenant | 26 | |
| platform/system | 21 | 原有 33 处保留 |
| platform/product | 16 | |
| platform/inventory | 16 | |
| platform/document | 10 | |
| platform/auth | 7 | 原有 6 处保留 |
| platform/file | 7 | |
| platform/approval | 6 | |
| platform/notify | 5 | |
| lms/tms | 12 | carrier 4 / delivery-area 3 / route 3 / freight quote 1 / tracking callback 1 |
| **合计** | **305** | 全量 404（ecom +34、scm +145、platform +114、lms-tms +12） |

**本次新增的 @PreAuthorize（按域，共 593 处）**

| 域 | 新增 | 全量 |
|---|---|---|
| ecom | 61 | 61 |
| scm | 311 | 311 |
| platform | 185 | 265 |
| lms-tms | 36 | 36 |
| **合计** | **593** | **673** |

全量 673 处里有 20 处是 `isAuthenticated()` 形态（全部是既有代码，新增的一律带权限码）；
其余 653 处注解 = 513 处 `hasAuthority('<code>')` + 140 处
`hasAnyAuthority('<primary>', '<other>', ...)`。

**为什么有 140 处多码注解（C1 修正）**：`ApiAccessControlFilter` 是 **anyMatch** ——
`ApiPermissionMatcher` 同时解析字面量行与 `{var}` 模板行，`/api/v1/fulfillments/page`
也会命中 `/api/v1/fulfillments/{fulfillmentNo}`，用户持有其中任一码即被 filter 放行。
只写一个码会把 filter 已放行的合法用户挡成 403（正是本设计的头号反目标）。因此凡是
请求 URL 的运行时候选码多于一个的端点，注解必须写成 `hasAnyAuthority(...)` 覆盖**全部**
候选；模板端点的候选取保守超集（filter 先跑，超集安全）。这 140 处含 4 处既有
platform-system 注解；`hasAuthority` 的单码形态仍是默认。CI 护栏
`check_permission_annotation_drift.py` 现在比较「注解码集合 == 运行时候选集合」，
不再只比对一条目录行 —— 否则这类收窄无法被 CI 发现。

### 其他两项交付的现状（2026-11-13）

- **读写分离（G3）**：7 个服务已激活 —— `platform-product`（group `product`）+ Phase 1 的
  6 个（`scm-order`/`order`、`platform-inventory`/`inventory`、`scm-warehouse`/`warehouse`、
  `scm-supplier`/`supplier`、`scm-purchase`/`purchase`、`lms-tms`/`logistics`）。标准形态 =
  `spring.datasource.dynamic.enabled=false` + `spring.datasource.rw.*`（`default-target: MASTER`）；
  从库 URL 默认回落主库（`${DB_SLAVE_HOST:${DB_HOST:localhost}}`），接真副本只改
  `DB_SLAVE_*`；每个服务暴露 `readwrite` actuator 端点，Phase 1 的 6 个服务各有一个静态
  YAML 测试 `ReadWriteApplicationConfigTest`（CI 不启动 Spring 上下文，它就是唯一的自动化护栏）。
- **分区维护调度（G4）**：compose `maintenance` profile 的 `db-maintenance` 服务（runner
  `loop` 模式）+ k8s 两个 CronJob（`0 3 1 * *` → `once all`；`0 3 * * 1` → `once drop`
  与 `once retention`，两者都跑，任一失败即非 0 退出）。
- **CI 护栏（G5）**：`scripts/ci/check_permission_annotation_drift.py` 已接入
  `.github/workflows/maven-build.yml`（当前 `errors=0, warnings=21`，warnings 全部是
  platform/system 与 platform/auth 上「目录里没有该 (path, method) 绑定」的既有注解），
  连同两个测试脚本（`test_check_permission_annotation_drift.py`、
  `test_permission_rules_conformance.py`）一起在 PR 与 push 上跑。

### 交付顺序（builder 执行顺序）

1. **P1 @AuditLog 扫描**（低风险、纯注解，先交付）：按服务提交，每服务一次提交。
2. **P2 权限码映射表生成 + @PreAuthorize 扫描 + D1 缺口报告**。
3. **P3 G5 CI 脚本挂入 workflow**（P2 全绿后）。
4. **P4 RW 激活**：scm-order 先行（模板现成）→ 其余 5 个服务 → 每服务独立提交。
5. **P5 维护自动化**（compose + k8s + runbook）。

### 待评审开放点

builder check（2026-11-12/13）产生的 4 个问题已全部核实并落为 D3/D8/D9/D10 与
§5.1–5.3 修订，无遗留问题需要用户拍板。评审核实过程中顺带确认的事实（可直接作为
执行依据）：

- `JwtAuthenticationFilter` 逐请求把解析出的权限码挂成 authorities，
  `hasAuthority('<code>')` 形态可行（platform-system 生产在用）。
- `AuditLog.recordParams` 默认 true，D7 准确。
- `create-partitions.sh` / `create-audit-partitions.sh` / `drop-old-partitions.sh`
  幂等性属实；`maintenance` profile 名在 docker-compose.yml 中未被占用；
  platform-inventory/platform-audit 的 `<classifier>exec</classifier>` 事项与
  AGENTS.md 一致。
- seed 019 头部声明目录就是为「22 个未打 @PreAuthorize 的业务服务」做的 URL 权限
  登记——G2 扫描正是该登记的另一半（注解侧），方向与原始设计意图一致。
