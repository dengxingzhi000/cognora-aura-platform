# 注解补齐与闲置设施激活 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已建成但未应用的能力落到代码上：全部业务服务的写端点补 `@AuditLog`、全部端点补 `@PreAuthorize`（码来自权限目录）、6 个独立库服务激活读写分离（platform-product 形态）、分区/保留脚本自动调度（compose + k8s），并以 CI drift 检查护栏。

**Architecture:** 注解扫描是纯注解变更（切面/方法安全已自动装配，无需新 Java 代码）；权限码从 `scripts/db/seed/*.sql` 目录机械推导，先建映射工具 + CI 检查器再动手扫；读写分离按 platform/product 已验证的 `dynamic.enabled=false + rw.default-target=MASTER` 形态逐服务启用，从库默认落主库（零基建变更）；维护自动化只调用既有 4 个脚本，不改其语义。

**Tech Stack:** Java 21 / Spring Boot 4.1 / Maven（parent `com.scm.parent`，无根 pom）/ MyBatis-Plus / baomidou dynamic-datasource + 自研 data-rw / PostgreSQL 18 / Python 3（工具与 CI 脚本）/ docker-compose + k8s manifests。

**Spec:** `docs/superpowers/specs/2026-11-12-audit-permission-rw-maintenance-activation-design.md`（本计划从 spec 出发；执行者两份都要读）。

## Global Constraints

- 所有 Maven 命令：`mvn ... -f com.scm.parent/pom.xml`；`-pl` 用 `../<module>` 相对路径；首次需 `mvn install -f bom/pom.xml`。
- 单模块验证命令模板：`mvn clean test-compile test -pl ../<module-path> -f com.scm.parent/pom.xml`。
- 不改 `com.cognora.*` 包名、不改 `cognora.*` 配置前缀；不引入新 Java 依赖。
- 注解-only 变更与 yml/配置变更**分开提交**；按任务一次 commit。
- 豁免路径的事实源 = `platform/common/web` 的 `ApiAccessControlProperties` 默认值（whitelist / exemptPaths / exemptPathExclusions / internalSignaturePaths / signatureOrPermissionPaths），Nacos 覆盖视为运行时叠加；仓库 yml 未覆盖这些列表。
- `.vmcheck/`、`enterprise-agent/`、`cognora-aura-web/`、`*/target/` 不是构建的一部分，一切扫描/脚本必须排除。
- mojibake 中文注释不修复（除非本次本来就在改写该注释）；seed 019 头部「templated 未生效」注释是事实错误，按 D9 修正（属改写，允许）。
- 不修改任何 request mapping、不动 `db_audit` schema、不自动补权限目录数据（缺口只报告）。
- CI 会跑 `check_openapi_drift.py`：本次不动 lms-tms / platform-inventory 的 request mapping，无需更新 OpenAPI 快照。
- `spring-boot-maven-plugin` 默认 skip；本次不新增模块、不改 pom 构建结构。

## Review Focus

1. **@PreAuthorize 码与目录漂移 → 合法用户 403**。期望：每个注解码都能在目录中按 (url, method) 命中且一致。Pin：Task 2 的 checker fixture 测试；Task 7–10 每步跑 checker。
2. **注解打在豁免端点（HMAC / 渠道回调 / whitelist / signatureOrPermission）→ 服务间调用、支付回调断链**。期望：这些端点两类注解都不打。Pin：Task 1 输出中 exempt 标记 + Task 3–6 验证步骤对豁免端点逐一 grep 确认无注解。
3. **RW 配置形态错误（dynamic 冲突、缺普通数据源回退）→ 服务启动失败**。期望：六服务统一 product 形态；`ReadWriteProperties` 中真实存在的键。Pin：Task 11–16 的「属性名对照 ReadWriteProperties」验证步骤 + 编译；启动验证标注为 VM 手工步骤。
4. **@AuditLog 打到 GET 或 riskLevel 失当 → 审计噪音/高危操作漏审计**。期望：仅非豁免写端点有注解；4=删除/授权/凭证/资金，3=增改/流转，2=导出/上报，1=确认/扫描。Pin：Task 3–6 验证步骤的计数核对（@AuditLog 数 == 非豁免写端点数）。
5. **维护调度 env 用错变量名（PG* vs DB_*）→ 静默永远连 localhost**。期望：env 用 `DB_HOST/DB_PORT/DB_USER/DB_PASSWORD`（+ `CH_*`）。Pin：Task 17 的 `once` 模式首跑输出检查（psql 目标 host 出现在日志）。

---

### Task 1: 权限目录映射工具 + 缺口报告（+ seed 019 注释修正）

**Files:**
- Create: `scripts/tools/permission_annotation_map.py`
- Create: `docs/superpowers/specs/2026-11-12-permission-mapping.json`（脚本输出，入库）
- Create: `docs/superpowers/specs/2026-11-12-permission-catalog-gaps.md`（D1 报告）
- Modify: `scripts/db/seed/019_api_permission_coverage.sql`（仅头部注释第二段：templated 行已生效——`ApiPermissionMatcher` 已实现并被 `platform/system/service/src/test/java/com/cognora/platform/system/service/Impl/SysPermissionServiceImplMatcherTest`、`.../support/ApiPermissionMatcherTest` 固化；删除「本段只登记定义，不改变当前放行行为」表述）

**Interfaces:**
- Produces（供 Task 3–10 消费）：`permission-mapping.json`，结构
  `{"services": {"<service-dir-rel-path>": {"endpoints": [{"file", "class", "javaMethod", "httpMethod", "path", "code", "exempt", "exemptReason"}]}}}`；
  `code` 为 null 且 `exempt=false` 的端点 = 目录缺口，进 gaps md。Task 3–6 用 `exempt=false` 且 httpMethod ∈ POST/PUT/DELETE/PATCH 的端点清单打 @AuditLog；Task 7–10 用 `exempt=false` 且 `code != null` 的全部端点打 @PreAuthorize。

- [ ] **Step 1: 写脚本骨架与目录解析**

Python 3，标准库 only。解析规则：
- 目录行：所有 `scripts/db/seed/*.sql`，正则匹配 `('CODE','NAME','MENU','URL','METHOD',N,N)` 形态（允许字段间空白/换行；`--` 注释行跳过），METHOD ∈ GET/POST/PUT/DELETE/PATCH/*。code/url/method 三元组入目录；重复 (url, method) 直接报错退出（目录应无重复——642 唯一对已核实）。
- controller 扫描：`platform|scm|ecom|lms` 四目录 `**/*Controller.java`，排除 `.vmcheck`、`enterprise-agent`、`cognora-aura-web`、`target`、`node_modules`；类级 `@RequestMapping("...")`（可无参）+ 方法级 `@(Get|Post|Put|Delete|Patch)Mapping(...)`（value 可缺省 = ""），拼完整 path；`{...}` 统一按占位符匹配（与目录行 `{id}` 等占位符等价）。
- 豁免判定：把 `ApiAccessControlProperties` 五个默认 List 的**当前实际值**（读该文件复制成脚本内常量，附来源注释；含 whitelist 尾段的 mall/members/promotions/sys-user-notification*/search/products/payments 自助路径、exemptPathExclusions 的 payments/page+refunds/page、internalSignaturePaths 全部 10 条、signatureOrPermissionPaths 的 `/api/v1/search/index/**`）与 normalized path 做 Ant 风格匹配（`**`→任意段、`*`→单段）。

- [ ] **Step 2: 运行并抽查映射正确性**

Run: `python scripts/tools/permission_annotation_map.py`
Expected: 生成两个输出文件。抽查断言（手工核对输出文件）：
- `PUT /api/v1/orders/{id}/cancel` → `order:order:cancel`
- `POST /invoice`（scm-finance）→ `finance:invoice:create`
- `POST /api/v1/orders/{orderNo}/pay`（order-center）→ `exempt=true`（internalSignature）
- `/api/v1/search/index/**` 下的端点 → `exempt=true`（signatureOrPermission）
- services 数 = 24（有 controller 的服务数：ecom 8 + lms 1 + platform 10 + scm 5）

- [ ] **Step 3: 提交**

```bash
git add scripts/tools/permission_annotation_map.py docs/superpowers/specs/2026-11-12-permission-mapping.json docs/superpowers/specs/2026-11-12-permission-catalog-gaps.md scripts/db/seed/019_api_permission_coverage.sql
git commit -m "chore(perms): catalog-to-controller mapping tool, gap report, fix stale seed-019 header"
```

### Task 2: @PreAuthorize ↔ 目录 drift 检查器（CI 护栏）

**Files:**
- Create: `scripts/ci/check_permission_annotation_drift.py`

**Interfaces:**
- Consumes: 与 Task 1 相同的目录/controller 解析与豁免规则（两个脚本共享规则，代码各自独立、豁免常量一致——在两个文件头互相注明对方路径，防止漂移）。
- Produces: 退出码 0/1，供 `.github/workflows/maven-build.yml` 后续接入（Task 19）；校验规则：每个 controller 上的 `@PreAuthorize("hasAuthority('<code>')")` 必须能按 (normalized path, httpMethod) 在目录中命中且 code 相等；豁免端点上的注解视为违规（防止把注解打回豁免端点）。

- [ ] **Step 1: 写失败测试（fixture）**

临时目录 fixture：mini seed 文件含一行 `('demo:thing:create','演示','menu:demo','/api/v1/things','POST',1,3)`；mini controller 含两个方法——`POST /api/v1/things` 注 `@PreAuthorize("hasAuthority('demo:thing:create')")`（正确）与注 `demo:thing:wrong`（错误）。
Run: `python scripts/ci/check_permission_annotation_drift.py --catalog <fixture-sql-dir> --controllers <fixture-java-dir>`，Expected: 退出码 1，错误信息含 `demo:thing:wrong` 与文件名。改回正确码 → 退出码 0。

- [ ] **Step 2: 实现检查器**

参数：`--catalog`（默认 `<root>/scripts/db/seed`）、`--controllers`（默认 `<root>` 四域目录）、`--root`。解析复用 Task 1 规则。mismatch/豁免端点带注解 → 打印 `file:line code expected` 并 exit 1；否则 exit 0。

- [ ] **Step 3: 对真仓库跑一次（应全绿——此刻注解只有 system/auth 既有码）**

Run: `python scripts/ci/check_permission_annotation_drift.py`
Expected: 退出码 0；platform-system / platform-auth 既有 61+19 处注解全部与目录一致（发现不一致即修复该注解——同 commit）。

- [ ] **Step 4: 提交**

```bash
git add scripts/ci/check_permission_annotation_drift.py
git commit -m "ci(perms): drift checker between @PreAuthorize and permission catalog"
```

### Task 3: @AuditLog 扫描 — ecom 域（8 服务）

**Files:**
- Modify: `ecom/{member,promotion,payment,order-center,mall,fulfillment,search,analytics}/*/src/main/java/**/*Controller.java`（以 Task 1 mapping 为准，精确到端点）

**Interfaces:**
- Consumes: Task 1 `permission-mapping.json`（exempt=false 且 httpMethod 为写方法 = 注解对象清单）。
- Produces: 无（纯注解）。

- [ ] **Step 1: 逐端点加注解**

对每个目标端点：`@AuditLog(operation = "<中文动词短句>", businessType = "<前缀>", riskLevel = <1-4>)`。
- businessType 前缀：member→`MEMBER`、promotion→`PROMOTION`、payment→`PAYMENT`、order-center→`ORDER_CENTER`、mall→`MALL`、fulfillment→`FULFILLMENT`、search→`SEARCH`、analytics→`ANALYTICS`；operation 从 controller 方法语义取（如「创建购物车条目」「提交退款申请」）。
- riskLevel：删除/授权/凭证/资金登记=4；创建/修改/状态流转=3；导出/批量查询/信息上报=2；确认/扫描=1。
- `recordParams` 默认 true；请求体含明文凭证（改密/重置类）才设 `recordParams = false`。
- 豁免端点（mapping 里 exempt=true，含 `/api/v1/payments`、`/api/v1/refunds` HMAC 端点、渠道回调、自助 whitelist 路径）**不打**。
- import：`com.cognora.platform.common.log.annotation.AuditLog`。风格对照 `scm/finance/.../controller/InvoiceController.java`。

- [ ] **Step 2: 编译 + 计数核对**

Run: 对 8 个模块逐个 `mvn clean test-compile -pl ../ecom/<svc>/service -f com.scm.parent/pom.xml`（member 等无 api 子模块的直接 service）
核对：`@AuditLog\(` 总数 == mapping 中 8 服务「exempt=false 写端点」总数（预期 ≈ 67 − 豁免数）；抽查 3 个豁免端点源码确认无注解。

- [ ] **Step 3: 提交**

```bash
git add ecom
git commit -m "feat(audit): @AuditLog on ecom write endpoints (8 services)"
```

### Task 4: @AuditLog 扫描 — scm 域（order/purchase/supplier/warehouse + finance 查漏）

**Files:**
- Modify: `scm/{order,purchase,supplier,warehouse}/service/src/main/java/**/*Controller.java`
- Modify: `scm/finance/service/src/main/java/**/*Controller.java`（仅当 checker/对照发现漏）

**Interfaces:**
- Consumes: Task 1 mapping。
- Produces: 无。

- [ ] **Step 1: 加注解**

前缀：order→`ORDER`、purchase→`PURCHASE`、supplier→`SUPPLIER`、warehouse→`WAREHOUSE`；riskLevel/豁免规则同 Task 3。scm-purchase 15 个 controller、67 写端点是大头；order 注意 `POST /api/v1/orders/{orderNo}/pay` 在 order-center 不在 order，但 order 若有 internalSignature 端点（对照 mapping）同样豁免。

- [ ] **Step 2: 编译 + 计数核对**

Run: 4 个模块 `mvn clean test-compile -pl ../scm/<svc>/service -f com.scm.parent/pom.xml`
核对：4 服务 @AuditLog 数 == mapping 非豁免写端点数（预期 ≈ 13+67+38+30 − 豁免）。

- [ ] **Step 3: 提交**

```bash
git add scm
git commit -m "feat(audit): @AuditLog on scm write endpoints (order/purchase/supplier/warehouse)"
```

### Task 5: @AuditLog 扫描 — platform 域（8 全新 + system/auth 查漏 + audit 判定）

**Files:**
- Modify: `platform/{inventory,product,tenant,approval,notify,document,file}/.../*Controller.java`
- Modify: `platform/system/.../controller/*.java`、`platform/auth/src/main/java/**/*Controller.java`（仅补漏）
- Modify: `platform/audit/.../*Controller.java`（判定：唯一写端点若是 `/sys-audit-log` 则豁免不动）

**Interfaces:**
- Consumes: Task 1 mapping。
- Produces: 无。

- [ ] **Step 1: 加注解**

前缀：inventory→`INVENTORY`、product→`PRODUCT`、tenant→`TENANT`、approval→`APPROVAL`、notify→`NOTIFY`、document→`DOCUMENT`、file→`FILE`。platform-system 现有 33 处保留，只补缺；platform-auth 现有 6 处保留（auth 的登录/登出/刷新语义照旧）。inventory 的 `/api/v1/inventory/reservation/**` 与 `/api/v1/pur-order/in-transit` 为 internalSignature 豁免。

- [ ] **Step 2: 编译 + 计数核对**

Run: 各模块 `mvn clean test-compile -pl ../platform/<svc>/... -f com.scm.parent/pom.xml`（auth 为平铺模块：`-pl ../platform/auth`）
核对：7 个全新服务计数对齐；system/auth 只增不减；audit 写端点确认豁免。

- [ ] **Step 3: 提交**

```bash
git add platform
git commit -m "feat(audit): @AuditLog on platform write endpoints, fill gaps in system/auth"
```

### Task 6: @AuditLog 扫描 — lms-tms 补 3 个 controller

**Files:**
- Modify: `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/{TmsCarrierController,TmsDeliveryAreaController,TmsRouteController}.java`

**Interfaces:**
- Consumes: Task 1 mapping。
- Produces: 无。

- [ ] **Step 1: 加注解**

businessType=`LOGISTICS`（与 TmsWaybillController 现有注解一致），写端点按 rubric。

- [ ] **Step 2: 编译 + 计数核对**

Run: `mvn clean test-compile -pl ../lms/tms/service -f com.scm.parent/pom.xml`
核对：lms 域 @AuditLog 总数 == mapping 非豁免写端点总数（29 − 豁免）。

- [ ] **Step 3: 提交**

```bash
git add lms
git commit -m "feat(audit): @AuditLog on remaining tms controllers"
```

### Task 7: @PreAuthorize 扫描 — ecom 域

**Files:**
- Modify: `ecom/*/.../*Controller.java`（mapping 中 exempt=false 且 code≠null 的全部端点，含 GET）

**Interfaces:**
- Consumes: Task 1 mapping（code 字段即注解码）。
- Produces: 无。

- [ ] **Step 1: 加注解**

`@PreAuthorize("hasAuthority('<code>')")`，code 原样取目录（如 `member:profile:update`）；import `org.springframework.security.access.prepost.PreAuthorize`；形态与 `platform/system/.../SysUserController.java` 一致。豁免端点不打。

- [ ] **Step 2: checker + 编译**

Run: `python scripts/ci/check_permission_annotation_drift.py`（Expected: 0）；8 模块 `mvn clean test-compile -pl ../ecom/<svc>/service -f com.scm.parent/pom.xml`。
核对：注解数 == mapping ecom 可注解端点数。

- [ ] **Step 3: 提交**

```bash
git add ecom
git commit -m "feat(perms): @PreAuthorize on ecom endpoints from permission catalog"
```

### Task 8: @PreAuthorize 扫描 — scm 域

**Files:**
- Modify: `scm/{order,purchase,supplier,warehouse,finance}/service/src/main/java/**/*Controller.java`

**Interfaces:**
- Consumes: Task 1 mapping。
- Produces: 无。

- [ ] **Step 1: 加注解**（同 Task 7 规则；finance 现无注解，全量按目录加）

- [ ] **Step 2: checker + 编译**

Run: checker Expected 0；4+1 模块 test-compile；计数核对。

- [ ] **Step 3: 提交**

```bash
git add scm
git commit -m "feat(perms): @PreAuthorize on scm endpoints from permission catalog"
```

### Task 9: @PreAuthorize — platform 域（查漏 system/auth + 全量其余）

**Files:**
- Modify: `platform/{inventory,product,tenant,approval,notify,document,file,audit}/.../*Controller.java`
- Modify: `platform/system/.../SysStatusDictController.java` 等 2 个无注解 controller、`platform/auth/.../*Controller.java`（补缺）

**Interfaces:**
- Consumes: Task 1 mapping。
- Produces: 无。

- [ ] **Step 1: 加注解**（system/auth 只补目录中存在而无注解的端点；其余服务全量）

- [ ] **Step 2: checker + 编译**（Run checker Expected 0；各模块 test-compile；计数核对）

- [ ] **Step 3: 提交**

```bash
git add platform
git commit -m "feat(perms): @PreAuthorize on platform endpoints, fill gaps in system/auth"
```

### Task 10: @PreAuthorize — lms-tms

**Files:**
- Modify: `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/*.java`

**Interfaces:**
- Consumes: Task 1 mapping。
- Produces: 无。

- [ ] **Step 1: 加注解**（同 Task 7 规则）

- [ ] **Step 2: checker + 编译**（Run checker Expected 0；`mvn clean test-compile -pl ../lms/tms/service -f com.scm.parent/pom.xml`；计数核对）

- [ ] **Step 3: 提交**

```bash
git add lms
git commit -m "feat(perms): @PreAuthorize on tms endpoints from permission catalog"
```

### Task 11: 读写分离激活 — scm-order（先行试点）

**Files:**
- Modify: `scm/order/service/src/main/resources/application.yml`
- Modify: `scm/order/service/src/main/java/com/cognora/scm/order/service/**`（query/command 或按方法语义）

**Interfaces:**
- Produces: 目标 YAML 形态（Task 12–16 复用同一形态，仅 group 名/db 名/env 差异）：
  `spring.datasource.dynamic.enabled: false` + `spring.datasource.rw.enabled: true` +
  `spring.datasource.rw.default-target: MASTER` + `rw.read-master-after-write: 2s` +
  `rw.replication-lag-tolerance: 1s` + `rw.load-balance: ROUND_ROBIN` +
  `rw.health-check-enabled: true` + `rw.health-check-interval: 30s` +
  `rw.slave-retry-count: 3` + `rw.groups.order.master/slaves`。

- [ ] **Step 1: 属性名对照**

Run: 对照 `platform/common/data-rw/.../ReadWriteProperties.java` 字段名
Expected: 上述每个键在 ReadWriteProperties（或其嵌套类）中有对应字段；不存在 keys 的键从列表去掉（以类为准，模板里出现过的键默认有效）。

- [ ] **Step 2: 改 application.yml**

保留现有 plain `datasource.url/username/driver` 块不动（rw 关闭时兜底，product 同款）；`dynamic` 块设 `enabled: false`；新增 rw 块：
```yaml
        rw:
          enabled: true
          default-target: MASTER
          read-master-after-write: 2s
          replication-lag-tolerance: 1s
          load-balance: ROUND_ROBIN
          health-check-enabled: true
          health-check-interval: 30s
          slave-retry-count: 3
          groups:
            order:
              master:
                url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/db_order?stringtype=unspecified
                username: ${DB_USERNAME:admin}
                password: ${DB_PASSWORD:changeme}
                driver-class-name: org.postgresql.Driver
              slaves:
                - name: slave1
                  url: jdbc:postgresql://${DB_SLAVE_HOST:${DB_HOST:localhost}}:${DB_SLAVE_PORT:${DB_PORT:5432}}/db_order?stringtype=unspecified
                  username: ${DB_SLAVE_USERNAME:${DB_USERNAME:admin}}
                  password: ${DB_SLAVE_PASSWORD:${DB_PASSWORD:changeme}}
                  driver-class-name: org.postgresql.Driver
```
（注意该服务现有 datasource 密码 env 实名以 application.yml 现值为准——若现值不是 `${DB_PASSWORD:changeme}` 就沿用现值。）

- [ ] **Step 3: 注解**

`service/query/**` 类级 `@Slave`；`service/command/**` 类级 `@Master`；无分层的类按方法语义（查询 `select/find/get/query/list/page/count/stat*` → @Slave；写 `save/create/update/delete/insert/deduct/lock/apply*/transition*` → @Master；语义不明不打 = 默认 MASTER）。import `com.cognora.platform.common.data.rw.annotation.{Master,Slave}`。

- [ ] **Step 4: 编译 + 提交**

Run: `mvn clean test-compile test -pl ../scm/order/service -f com.scm.parent/pom.xml` Expected: PASS（Context 不在 CI 启动——VM 启动验证列入 Task 19 runbook）

```bash
git add scm/order
git commit -m "feat(rw): activate read-write separation for scm-order (product form)"
```

### Task 12: 读写分离激活 — platform-inventory

**Files:**
- Modify: `platform/inventory/service/src/main/resources/application.yml`
- Modify: `platform/inventory/service/src/main/java/**`（command 服务已有 @Master/@Slave——只盘点补缺）

**Interfaces:**
- Consumes: Task 11 的 YAML 形态（group 名 `inventory`，db `db_inventory`）。
- Produces: 无。

- [ ] **Step 1: yml（同 Task 11 形态；plain url 块保留；master 凭据沿用现 env 名）**
- [ ] **Step 2: 盘点既有注解并补缺**（Run: grep `@Master|@Slave` 在 `platform/inventory/service/src/main/java`；command 已注解的不动，query 缺的补 `@Slave`）
- [ ] **Step 3: 编译 + 提交**（Run: `mvn clean test-compile test -pl ../platform/inventory/service -f com.scm.parent/pom.xml` Expected PASS）

```bash
git add platform/inventory
git commit -m "feat(rw): activate read-write separation for platform-inventory"
```

### Task 13: 读写分离激活 — scm-warehouse（baomidou 迁移形态）

**Files:**
- Modify: `scm/warehouse/service/src/main/resources/application.yml`
- Modify: `scm/warehouse/service/src/main/java/**`（按方法语义）

**Interfaces:**
- Consumes: Task 11 YAML 形态（group `warehouse`，db `db_warehouse`）。
- Produces: 「迁移形态」模板（Task 14–16 复用）。

- [ ] **Step 1: yml 迁移**

现状是激活的 `dynamic.primary: master` + `dynamic.datasource.master.{...}`（无 plain url）。目标：
- 新增 plain 回退块 `datasource.url/username/password/driver-class-name`（值 = 现 master 块的 env 表达式原样）；
- `dynamic` 改为仅 `enabled: false`（删除 `primary` 与 `datasource.master` 嵌套块）；
- 新增 rw 块（Task 11 形态，group `warehouse`，master url `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/db_warehouse?stringtype=unspecified`，凭据 env 与现 master 一致）。
- 该服务有 per-service Flyway——启动期 Flyway 落 default-target MASTER（product 注释确认此语义），无需额外配置。

- [ ] **Step 2: 注解（按方法语义；该服务无 query/command 分层则逐类判断）**

- [ ] **Step 3: 编译 + 提交**（Run: `mvn clean test-compile test -pl ../scm/warehouse/service -f com.scm.parent/pom.xml` Expected PASS）

```bash
git add scm/warehouse
git commit -m "feat(rw): activate read-write separation for scm-warehouse (migrate from baomidou)"
```

### Task 14: 读写分离激活 — scm-supplier

**Files:**
- Modify: `scm/supplier/service/src/main/resources/application.yml`、`.../src/main/java/**`

**Interfaces:**
- Consumes: Task 13 迁移形态（group `supplier`，db `db_supplier`；有 Flyway）。

- [ ] **Step 1: yml 迁移**（同 Task 13；url `db_supplier`）
- [ ] **Step 2: 注解**（同 Task 13 规则）
- [ ] **Step 3: 编译 + 提交**（Run: `mvn clean test-compile test -pl ../scm/supplier/service -f com.scm.parent/pom.xml` Expected PASS）

```bash
git add scm/supplier
git commit -m "feat(rw): activate read-write separation for scm-supplier"
```

### Task 15: 读写分离激活 — scm-purchase

**Files:**
- Modify: `scm/purchase/service/src/main/resources/application.yml`、`.../src/main/java/**`

**Interfaces:**
- Consumes: Task 13 迁移形态（group `purchase`；master url 为 `${DB_NAME:db_purchase}`——迁移时保留该 env 名）。

- [ ] **Step 1: yml 迁移**（同 Task 13；master/slave url 均用 `${DB_NAME:db_purchase}`）
- [ ] **Step 2: 注解**（同 Task 13 规则）
- [ ] **Step 3: 编译 + 提交**（Run: `mvn clean test-compile test -pl ../scm/purchase/service -f com.scm.parent/pom.xml` Expected PASS）

```bash
git add scm/purchase
git commit -m "feat(rw): activate read-write separation for scm-purchase"
```

### Task 16: 读写分离激活 — lms-tms

**Files:**
- Modify: `lms/tms/service/src/main/resources/application.yml`、`.../src/main/java/**`

**Interfaces:**
- Consumes: Task 13 迁移形态（group `logistics`，db `db_logistics`）。

- [ ] **Step 1: yml 迁移**（同 Task 13）
- [ ] **Step 2: 注解**（同 Task 13 规则）
- [ ] **Step 3: 编译 + 提交**（Run: `mvn clean test-compile test -pl ../lms/tms/service -f com.scm.parent/pom.xml` Expected PASS）

```bash
git add lms/tms
git commit -m "feat(rw): activate read-write separation for lms-tms"
```

### Task 17: 维护调度 runner + compose `maintenance` profile

**Files:**
- Create: `deploy/maintenance/run-maintenance.sh`
- Modify: `docker-compose.yml`（新增 `db-maintenance` 服务，`profiles: [maintenance]`）

**Interfaces:**
- Consumes: 既有 `scripts/db/partition/{create-partitions,create-audit-partitions,drop-old-partitions}.sh`、`scripts/db/retention/apply-retention.sh`（只调用不改）。
- Produces: `run-maintenance.sh` 两种用法：`run-maintenance.sh loop`（默认，无限循环：月度对每 30d 跑 create 两个脚本，周度每 7d 跑 drop+retention，首跑立即）与 `run-maintenance.sh once <create|create-audit|drop|retention>`（k8s CronJob 用）；env：`DB_HOST/DB_PORT/DB_USER/DB_PASSWORD`、`CH_HOST/CH_PORT/CH_USERNAME/CH_PASSWORD`（D10）。

- [ ] **Step 1: 写 runner**

纯 bash；循环用 `sleep` + 天数换算（不用 cron）；每个任务调用前 `echo "[maintenance] <task> start $(date -Iseconds)"`，失败继续下一个任务（下轮重试），全部输出 stdout。

- [ ] **Step 2: compose 服务**

```yaml
  db-maintenance:
    image: postgres:18
    container_name: scm-db-maintenance
    profiles: [maintenance]
    volumes:
      - ./scripts/db:/opt/db:ro
      - ./deploy/maintenance/run-maintenance.sh:/opt/run-maintenance.sh:ro
    environment:
      DB_HOST: postgres
      DB_PORT: "5432"
      DB_USER: ${POSTGRES_USER:-admin}
      DB_PASSWORD: ${POSTGRES_PASSWORD:-admin123}
      CH_HOST: clickhouse
      CH_PORT: "8123"
    command: ["bash", "/opt/run-maintenance.sh", "loop"]
    depends_on: [postgres]
    restart: unless-stopped
```
（挂载点内脚本相对路径：runner 内以 `/opt/db` 为基准调用既有脚本；若既有脚本内部依赖自身目录，runner 先 `cd /opt/db/<subdir>` 再调用。）

- [ ] **Step 3: once 模式验证**

Run: `bash deploy/maintenance/run-maintenance.sh once create` 在**无 DB 环境**下运行
Expected: 脚本对 `psql` 调用失败但日志清楚显示目标 `DB_HOST` 值（Review Focus #5 的 pin：连接目标可见即算验证通过；真实连库验证属 VM 步骤）。

- [ ] **Step 4: 提交**

```bash
git add deploy/maintenance/run-maintenance.sh docker-compose.yml
git commit -m "feat(ops): scheduled partition/retention maintenance via compose profile"
```

### Task 18: k8s CronJob（monthly/weekly）

**Files:**
- Create: `deploy/k8s/db-maintenance-cronjob.yml`

**Interfaces:**
- Consumes: Task 17 runner 的 `once` 模式；`deploy/k8s/secrets.yml`（`scm-secrets`：DB_USERNAME 等）、configmap `scm-config`。
- Produces: 无。

- [ ] **Step 1: 两个 CronJob**

monthly（`0 3 1 * *`：create + create-audit + drop + retention 全跑一遍兜底）与 weekly（`0 3 * * 1`：drop + retention）；镜像 `postgres:18`；`command: ["bash", "/opt/run-maintenance.sh", "once", "<task>"]`；挂载 runner + scripts（ConfigMap 挂 scripts 不现实——scripts 用 initContainer 从 git 同步不可行；采用：把 runner 脚本内容放进 ConfigMap `db-maintenance-scripts`，CronJob 挂载；scripts/db 的 4 个脚本**内容也进同一 ConfigMap**（键名=脚本相对路径），由 runner 在执行前落到 `/opt/db/...` 后调用）；env 引用 `scm-secrets`；`concurrencyPolicy: Forbid`、`restartPolicy: OnFailure`、`successfulJobsHistoryLimit: 1`、`failedJobsHistoryLimit: 3`。风格对齐 `deploy/k8s` 既有单文件 manifest。

- [ ] **Step 2: 静态校验**

Run: `kubectl apply --dry-run=client -f deploy/k8s/db-maintenance-cronjob.yml`（或无 kubectl 时用 yamllint/肉眼）
Expected: 解析无误；env 值来自 secretKeyRef。

- [ ] **Step 3: 提交**

```bash
git add deploy/k8s/db-maintenance-cronjob.yml
git commit -m "feat(ops): k8s CronJobs for partition/retention maintenance"
```

### Task 19: 文档收尾（runbook + AGENTS.md + .env.example）+ CI 接线

**Files:**
- Modify: `docs/operations/OPERATIONS_MANUAL.md` 或 `docs/operations/runbooks/` 新增「分区与保留策略自动调度」节
- Modify: `AGENTS.md`（三处：Read-write separation 一节改为「product + 6 个 Phase-1 服务」并注明 product 形态为标准形态；Audit trail 一节补「全服务写端点已注解 @AuditLog，豁免清单见 ApiAccessControlProperties」；Partition 一节补调度方式）
- Modify: `.env.example`（`DB_SLAVE_HOST/DB_SLAVE_PORT/DB_SLAVE_USERNAME/DB_SLAVE_PASSWORD` 注释：留空 = 指向主库）
- Modify: `.github/workflows/maven-build.yml`（build job 中 OpenAPI drift 校验之后追加 `python3 scripts/ci/check_permission_annotation_drift.py`）

**Interfaces:**
- Consumes: Task 2 checker、Task 17 runner、Task 11–16 RW 形态。
- Produces: 无。

- [ ] **Step 1: 写文档与 runbook**（含 VM 启动验证清单：六服务逐个 `docker-compose up` 后看 `[RW-Config] Group [x] configured` 日志与 `/actuator/health`；`db-maintenance` 首跑日志核对）；同时把 before/after 注解统计（Task 3–10 各步的计数结果）回填到 spec `2026-11-12-...-design.md` §7 表格
- [ ] **Step 2: workflow 接线**（Run: checker 全仓 Expected 0 后才允许合入）
- [ ] **Step 3: 提交**

```bash
git add docs AGENTS.md .env.example .github/workflows/maven-build.yml
git commit -m "docs+ci: rw activation notes, audit coverage, maintenance runbook, perms drift check in CI"
```

---

## 交付顺序与回退

- Task 1→2 必须最先（后续任务的输入与护栏）；Task 3–10 注解类可并行但建议按序；Task 11–16 严格逐服务（scm-order 先行验证形态）；Task 17–18 独立；Task 19 收尾。
- 任一 RW 服务本地编译通过但 VM 启动失败：回退 = 该服务还原本次 yml/注解改动（单独 commit），其余服务不受影响；`ReadWriteDatasourceConflictChecker` 的报错信息是第一诊断依据。
- gap 报告（Task 1 产出）中的端点保持无注解（现状即 403），是否补目录行由用户另行决定。
