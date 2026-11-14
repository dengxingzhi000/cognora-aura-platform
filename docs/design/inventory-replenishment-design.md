# 库存智能补货链 — 深度功能设计

> 版本：v0.2（MVP 规则版已落地，见 §11 实施对照）
> 日期：2026-11-10
> 范围：`platform-inventory` + `scm-purchase` + `platform/common/decision-engine`
> 状态标注：`已存在` = 代码核实存在；`新增` = 本设计 proposed；`待核实` = 需实现前确认
> 关联：见 `architecture-review.md`（P0/P1 前置）、`inventory-replenishment-mvp-plan.md`（实现规划）

---

## 1. 设计目标

把现有「静态告警 → 手动采购计划」升级为「**需求驱动 → 决策引擎算量选源 → 补货建议单 → 人审 → 采购计划/采购单 → 收货回写 → 反馈闭环**」。

- **北极星**：自动补货建议采纳率 + 补货后缺货率（双指标）。
- **反指标**：呆滞库存金额占比、超储 SKU 数（防止为降缺货而过量囤货）。
- **不做什么**：不新建微服务；不一步到 LSTM；不自动下单（永远人审草稿）。

---

## 2. 现状锚点（已核实）

| 环节 | 证据 | 现状结论 |
|---|---|---|
| 告警扫描 | `platform/inventory/.../service/command/InvAlertCommandService.java:96` | 只比 `availableStock ≤ safetyStock`，无需求感知、**未算在途** |
| 安全库存 | `Inventory.safetyStock`（`domain/entity/Inventory.java:67`）+ 在研 `updateSafetyStock` | 手动值，历史硬编码 10，无动态计算 |
| 采购计划 | `scm/purchase/.../service/command/PurPlanCommandService.java` | ~~误置 REJECTED / 空操作~~ **已修**（见 §11.1） |
| 计划明细 | `PurPlanItem`（`materialCode/materialName/plannedQuantity/budgetPrice`） | **material 口径，非 skuId**（补货→计划需字段映射） |
| 决策框架 | `decision-engine`：`RankingEngine`/`AllocationEngine`/`SpelScorer`/`ConstraintChain`/`WeightProfile`/`DecisionFeedback` | 通用打分/排序框架已就绪，**未接补货** |
| 需求信号 | inv_log 出库流水 + order items(skuId) | 有原始信号，无聚合/预测 |
| 采购下游 | PurRfq→Quotation→PriceComparison→Order→Receipt controller 全在 | 结构齐全，可承接 |
| 迁移基建 | `platform/inventory/.../resources/db/migration/V1__stocktake.sql` + starter 已引 | 新增走 `V2__replenishment.sql`（per-service Flyway） |

---

## 3. 端到端业务流程

```
出库流水/订单明细
   │  (需求聚合 Job)
   ▼
需求预测 μ/σ (每 SKU×仓)
   │
   ▼
补货决策引擎 ── 触发判定: 可用 + 在途 ≤ ROP
   ├ 算补货量 Q (目标水位 − 可用 − 在途, 受 MOQ/仓容/预算/效期约束)
   ├ 选供应商 (decision-engine 打分: 价格/交期/履约/账期)
   └ (可选) 多仓分配
   │
   ▼
补货建议单 (DRAFT, 存决策快照+原因)
   │  人工复核/改量/驳回 (人在环)
   ▼
ADOPTED ──→ 采购计划 PurPlan(DRAFT) ──→ 审批 APPROVED ──→ 采购单 PurOrder
                                                          │
                                                     收货 PurReceipt → 入库回写 inv_inventory
   │
   └ 采纳/驳回/改量原因 ──→ DecisionFeedback ──→ 校正 WeightProfile / 预测偏差 (闭环)
```

---

## 4. 决策点规格

### 4.1 需求预测
- **输入**：inv_log 出库流水（按 SKU×仓×天，**扣除非销售出库**：调拨/报损）、订单明细、促销日历、季节标记。
- **分档**：MVP 加权移动平均 → 统计版 Holt-Winters → ML 版 Prophet/GBDT（仅当统计版 MAPE 证明不足）。
- **产出**：`forecastDailyDemand μ`、`demandStdDev σ`、`confidence`、`mape`、`method`、`version`。
- **冷启动**：历史 < N 天 → 同类目均值 + `LOW_CONFIDENCE` 标记，建议单转「需人工」。

### 4.2 动态安全库存 + 再订货点
- **输入**：μ/σ、供应商交期 `L`（历史收货 lead time 均值）、交期波动 `σL`、服务水平 `Z`（按 ABC 分级：A=98%、B=95%、C=90%）。
- **公式**：
  - `SS = Z × sqrt(L × σ² + μ² × σL²)`
  - `ROP = μ × L + SS`
- **触发**：`availableStock + inTransitQty ≤ ROP`（**inTransit = PurOrder 未收货量，必须算，否则重复补货**）。
- **人工覆盖**：`Inventory.safetyStock/maxStock` 作为人工上下限，人工优先。

### 4.3 补货量
- **目标水位法**：`Q = targetStock − availableStock − inTransitQty`；`targetStock = μ × (L + reviewPeriod) + SS`。
- **约束（decision-engine ConstraintChain）**：供应商 MOQ、包装倍数、仓容 `maxStock`、采购预算 `totalBudget`、效期商品不超剩余保质可销量。
- **产出**：`suggestedQty` + `reasonSnapshot`（命中规则解释，供人审）。

### 4.4 供应商选择（复用 decision-engine）
- **复用**：`RankingEngine.rank()` + `SpelScorer` + `WeightProfile`（按租户/品类配权重）+ `ConstraintChain`（资质有效期/黑名单/MOQ 硬淘汰）。
- **维度**：报价、交期、历史履约率、到货合格率、账期。
- **输入缺口**：`supplier_sku_perf`（供应商-SKU 绩效聚合）+ 供货关系；比价结果来自 PurQuotation/PriceComparison。

### 4.5 多仓分配（第二阶段，可选）
- 复用 `AllocationEngine` + `NearestNeighborOptimizer`；按各仓 ROP 缺口 + 到货成本分配；缺数据仓回退主仓。

### 4.6 闭环学习
- 复用 `DecisionFeedback`/`FeedbackRepository`/`DecisionObserver`；人审驳回/改量原因回流校正权重与预测偏差。

---

## 5. 数据模型（新增，DDL 草案见 §5.2）

| 表 | 用途 | 关键约束 |
|---|---|---|
| `inv_replenishment_policy` | SKU×仓补货策略（服务水平/复核周期/ABC/人工覆盖开关） | unique(tenant_id, sku_id, warehouse_id) |
| `inv_demand_forecast` | 预测 μ/σ/mape/method/version | unique(tenant_id, sku_id, warehouse_id, forecast_date, version) |
| `inv_replenishment_suggestion` | 补货建议单头（状态机） | unique(tenant_id, suggestion_no) |
| `inv_replenishment_suggestion_item` | 建议明细（SKU/仓/量/选源/快照） | fk head_id |
| `supplier_sku_perf` | 供应商-SKU 交期/履约/合格率聚合 | unique(tenant_id, supplier_id, sku_id) |

### 5.1 建议单状态机（复用 StocktakeStatus 同款写法）
```
DRAFT → ADOPTED → CONVERTED (转采购计划/单)
DRAFT → REJECTED
DRAFT → CANCELLED
ADOPTED → CANCELLED
```
- 只有 `ADOPTED` 可 `CONVERTED`；`CONVERTED/REJECTED/CANCELLED` 终态。
- 幂等：一个补货周期一个建议单，`businessKey = tenant + sku + warehouse + period`；已在途/已生成去重。

### 5.2 DDL 草案（`V2__replenishment.sql`，PostgreSQL，idempotent，全部带 tenant_id）

```sql
-- 目标库 db_inventory；全部 IF NOT EXISTS；无 DELETE/TRUNCATE
CREATE TABLE IF NOT EXISTS inv_replenishment_policy (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    abc_class VARCHAR(1) NOT NULL DEFAULT 'C',
    service_level NUMERIC(5,4) NOT NULL DEFAULT 0.9500,
    review_period_days INT NOT NULL DEFAULT 7,
    manual_override BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_repl_policy UNIQUE (tenant_id, sku_id, warehouse_id)
);

CREATE TABLE IF NOT EXISTS inv_demand_forecast (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    forecast_date DATE NOT NULL,
    daily_demand NUMERIC(18,4) NOT NULL,
    demand_std_dev NUMERIC(18,4) NOT NULL DEFAULT 0,
    mape NUMERIC(6,4),
    method VARCHAR(32) NOT NULL,
    confidence VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    version INT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_demand_forecast UNIQUE (tenant_id, sku_id, warehouse_id, forecast_date, version)
);
CREATE INDEX IF NOT EXISTS idx_demand_forecast_sku ON inv_demand_forecast(tenant_id, sku_id, warehouse_id) WHERE NOT deleted;

CREATE TABLE IF NOT EXISTS inv_replenishment_suggestion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    suggestion_no VARCHAR(64) NOT NULL,
    warehouse_id UUID,
    period VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_repl_status CHECK (status IN ('DRAFT','ADOPTED','CONVERTED','REJECTED','CANCELLED')),
    business_key VARCHAR(128) NOT NULL,
    item_count INT NOT NULL DEFAULT 0,
    total_suggested_qty BIGINT NOT NULL DEFAULT 0,
    pur_plan_id UUID,
    created_by UUID,
    adopted_by UUID,
    converted_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_repl_suggestion_no UNIQUE (tenant_id, suggestion_no),
    CONSTRAINT uk_repl_business_key UNIQUE (tenant_id, business_key)
);
CREATE INDEX IF NOT EXISTS idx_repl_suggestion_status ON inv_replenishment_suggestion(tenant_id, status) WHERE NOT deleted;

CREATE TABLE IF NOT EXISTS inv_replenishment_suggestion_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    suggestion_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    available_stock INT NOT NULL,
    in_transit_qty INT NOT NULL DEFAULT 0,
    reorder_point NUMERIC(18,4) NOT NULL,
    safety_stock NUMERIC(18,4) NOT NULL,
    suggested_qty INT NOT NULL,
    adopted_qty INT,
    supplier_id UUID,
    reason_snapshot TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_repl_item_head FOREIGN KEY (suggestion_id) REFERENCES inv_replenishment_suggestion(id)
);
CREATE INDEX IF NOT EXISTS idx_repl_item_head ON inv_replenishment_suggestion_item(suggestion_id) WHERE NOT deleted;

CREATE TABLE IF NOT EXISTS supplier_sku_perf (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    supplier_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    avg_lead_time_days NUMERIC(8,2),
    lead_time_std_dev NUMERIC(8,2),
    fulfillment_rate NUMERIC(5,4),
    quality_pass_rate NUMERIC(5,4),
    sample_count INT NOT NULL DEFAULT 0,
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_supplier_sku_perf UNIQUE (tenant_id, supplier_id, sku_id)
);
```

> `supplier_sku_perf` 归属待定：供应商绩效更自然属于 `scm-supplier`/`db_supplier`；若放此处需跨库读取供应商，违反 CQRS 边界。**建议放 db_supplier，由 supplier 服务聚合，inventory 通过 Dubbo 查询**（待核实 supplier 现有绩效表）。DDL 先列此处占位，实现前定位归属。

---

## 6. 接口契约（新增，走现有 ApiResponse 封装 + 权限目录登记）

网关前缀 `/platform-inventory/api/v1/inventory/replenishment/**`（与现有 `/api/v1/inventory/*` 一致）。

| 方法 | 路径 | 说明 | 权限码（需登记 seed） |
|---|---|---|---|
| POST | `/suggestions/generate` | 手动触发一次补货计算（按仓/范围），生成 DRAFT 建议单 | `inventory:replenishment:generate` |
| POST | `/suggestions/query` | 分页查询建议单 | `inventory:replenishment:view` |
| GET | `/suggestions/{id}` | 建议单详情（含明细+决策快照） | `inventory:replenishment:view` |
| PUT | `/suggestions/{id}/items/{itemId}` | 人工改量/换供应商（仅 DRAFT） | `inventory:replenishment:edit` |
| PUT | `/suggestions/{id}/adopt` | 采纳（DRAFT→ADOPTED） | `inventory:replenishment:adopt` |
| PUT | `/suggestions/{id}/reject` | 驳回（DRAFT→REJECTED，带原因） | `inventory:replenishment:adopt` |
| POST | `/suggestions/{id}/convert` | 转采购计划（ADOPTED→CONVERTED，异步事件驱动） | `inventory:replenishment:convert` |
| POST | `/policies` / `/policies/query` | 补货策略维护 | `inventory:replenishment:policy` |
| GET | `/forecasts/{skuId}` | 查预测（解释用） | `inventory:replenishment:view` |

> 全部登记进权限目录（否则默认 DENY）；写操作 `operatorId` 从登录态取，不接受前端传（对齐现有 self-service 约定）。

---

## 7. 架构落点与约束

- **预测/算量在查询侧**：`service/query/`，`@Slave`；重计算走 XXL-Job（仿 `InventoryAlertScanJobHandler`）。**严禁进库存热点路径**（AGENTS：热点只 Redis+Lua）。
- **decision-engine 进程内调用**（library，非服务）：选源/分仓不新增同步 RPC 跳数。
- **转采购计划跨服务**：inventory→purchase 用**领域事件 + outbox**（已有 outbox 基建），异步、幂等（`businessKey=建议单号`）。**不用长分布式事务串预测→采购**。
- **租户**：Job 无上下文时按租户循环 `TenantContextHolder.executeInTenantContext`。
- **字段映射**：建议单 SKU 口径 → PurPlanItem `materialCode`；需 SKU→material 映射（待核实商品主数据关系），映射缺失转「需人工」。

---

## 8. 异常态与人在环

| 场景 | 处理 |
|---|---|
| 新品无历史 | 同类均值 + LOW_CONFIDENCE，建议单标「需人工」 |
| 无 lead time / 无供应商关系 | 标「不可自动下单」，仅提示 |
| MOQ/仓容/预算冲突 | 给可行替代量 + 原因，不静默丢弃 |
| SKU→material 映射缺失 | 转采购计划步骤失败并回写建议单「待补映射」 |
| 重复触发 | business_key 幂等，返回已有建议单 |

---

## 9. 前置依赖与风险

1. 需求信号可信 → inv_log 出库口径正确区分销售/调拨/报损（**待核实**）。
2. 采购计划状态机可信 → 修 `PurPlanCommandService.complete/startExecution` 两个 bug。
3. 「放开自动下单」排在 `architecture-review.md` 的 P0-1/P0-2（交易/权限闭环）之后。
4. `supplier_sku_perf` 归属跨库问题（§5.2 注）实现前定位。

---

## 10. 分阶段验收

| 阶段 | 范围 | 验收 |
|---|---|---|
| MVP 规则版 | 移动平均 + ROP(含在途) + 目标水位量 + 单供应商 + 建议单草稿→人审→转 PurPlan | 历史回测：缺货触发命中率、建议量 vs 人工实采偏差 ≤ 阈值；「在途去重不重复下单」测试通过 |
| 统计版 | 动态 SS + ABC 分级 + decision-engine 选源 + 反馈闭环 | MAPE 报告；选源打分可解释；采纳/驳回回流 |
| ML 版（可选） | Prophet/GBDT + 多仓分配 | 仅当统计版 MAPE 不足才启动；A/B 对比 |

> 维护：本文件为设计草案；实现落地后同步更新表/接口/状态，DDL 以 `V2__replenishment.sql` 为准。

---

## 11. 实施对照（MVP 规则版，2026-11-10 落地）

### 11.1 前置修复

| 问题 | 修复 |
|---|---|
| `complete()` 走 `APPROVED→REJECTED` 且落 `status=3` | 新增 `PurPlanStatus`（`0 草稿/1 待审批/2 已审批/3 已完成/4 执行中`），`complete()` = `EXECUTING→COMPLETED` |
| `startExecution()` 是 `APPROVED→APPROVED` 空操作 | 改为 `APPROVED→EXECUTING`，真正落执行态 |
| `chk_plan_status CHECK (status IN (0,1,2,3))` 挡住执行态 | `scripts/db/migrations/purchase_001_plan_executing_status.sql` 放宽到 4 |
| `PurPlanServiceImpl` 与 `PurPlanCommandService` 各存一份流转实现 | Service 委托 Command（单一实现，两条路径不会漂移） |
| 流转依赖 `StatusValidator`（Dubbo，词表与 SMALLINT 编码不对应） | 四条流转**全部**改走进程内枚举状态机，`PurPlanCommandService` 已彻底移除 `StatusValidator` 依赖 |

> 最后一条当时只改了一半：`submit/approve` 仍调 `StatusValidator`，而
> `startExecution/complete` 只走枚举。结果是 **platform-system 不可用时计划能审批
> 却无法开始执行**，故障表现取决于调哪个动作。现已统一，`mvn test` 不再需要
> 任何 Dubbo 桩；`PurPlanCommandServiceTest.allTransitionsWorkWithoutStatusDictionary`
> 钉住四条流转的编码序列。

### 11.2 落地清单

**platform-inventory**

| 层 | 文件 |
|---|---|
| 迁移 | `resources/db/migration/V2__replenishment.sql`（4 表，全部 `IF NOT EXISTS` + `tenant_id`） |
| 枚举 | `domain/ReplenishmentStatus`、`domain/ReplenishmentFlags` |
| 实体 | `InvReplenishmentPolicy`、`InvDemandForecast`、`InvReplenishmentSuggestion`、`InvReplenishmentSuggestionItem` |
| Mapper | 上述 4 个 + `InvDemandAggregationMapper`（销售出库口径聚合） |
| 纯函数 | `service/replenishment/{ReorderPointCalculator, ReplenishmentConstraintChecker, WeightedMovingAverage}` |
| 查询 | `DemandAggregationQueryService`（`@Slave`）、`ReplenishmentSuggestionQueryService`、`ReplenishmentPolicyQueryService` |
| 编排 | `ReplenishmentComputeService`（`@Master`，幂等 + 在途去重 + 约束裁剪） |
| 命令 | `ReplenishmentSuggestionCommandService`（adopt/reject/cancel/convert）、`ReplenishmentPolicyCommandService` |
| 端口 | `PurchaseInTransitPort`、`SkuMaterialMappingResolver`（+ `Empty`/`None` 显式降级实现） |
| 事件 | `ReplenishmentConvertedEvent`（outbox → Kafka `cognora.replenishmentsuggestion`） |
| 接口 | `ReplenishmentController`（`/api/v1/inventory/replenishment/**`，14 个端点） |
| Job | `ReplenishmentComputeJobHandler`（按租户循环 `executeInTenantContext`） |

**scm-purchase**

| 项 | 文件 |
|---|---|
| 状态机 | `domain/PurPlanStatus` + `PurPlanStatusTest` |
| 转换 | `PurPlanCommandService.createFromReplenishment`（`plan_no` 幂等） |
| 消费 | `listener/ReplenishmentConvertedConsumer`（剥两层信封、`executeInTenantContext`） |

**权限**：`scripts/db/seed/033_replenishment_permissions.sql`（14 条，一码一端点）

### 11.3 与设计的偏差（有意为之）

1. **`supplier_sku_perf` 未建**：归属 `db_supplier`（供应商域），放 `db_inventory` 会让库存服务跨库读供应商，违反 CQRS 边界。留统计版，由 supplier 服务聚合、inventory 经端口查询。
2. **`decision-engine` 未接入 MVP**：规则版只用纯函数做算量约束；选源打分留统计版（设计 §4.4）。
3. **在途分两段**：`采购在途`（port，跨服务）+ `建议在途`（本库 `ADOPTED`/`CONVERTED` 的采纳量）。设计只写了前者；后者是防止「建议单还没转成采购单」这段空窗期重复补货。
4. **一码一端点**：`sys_permission` 的唯一索引是 `(tenant_id, permission_code)`，一个动作码无法覆盖多个端点（后插入会覆盖前者的 `api_path`），因此按端点细分后缀。
5. **转换失败不回收决策**：`convert` 失败只回写 `convert_error`，单据停在 `ADOPTED` 供补映射后重试（设计 §8 的「待补映射」）。

### 11.4 跨服务闭环（已接通）

两个端口都已落成生产实现，不再依赖空桩：

| 端口 | 实现 | 下游端点 |
|---|---|---|
| `PurchaseInTransitPort` | `HttpPurchaseInTransitClient` | `GET /api/v1/pur-order/in-transit`（scm-purchase） |
| `SkuMaterialMappingResolver` | `ProductSkuMaterialMappingResolver` | `GET /api/v1/prod-sku/{id}`（platform-product） |

两者都由 `ReplenishmentPortConfiguration` 装配，走 `@LoadBalanced` RestClient +
**服务间 HMAC 签名**（`RestClientRequestSignatureInterceptor`）+ `X-Tenant-Id` 透传。
采购在途端点登记在 `ApiAccessControlProperties.internalSignaturePaths`（不是
`exemptPaths`）——不验签就等于任何人可拉全租户采购在途。

**超时**：采购 2s/5s、商品 2s/3s，超时即降级，不让批处理被下游拖死。

**在途去重的三条口径**（这是「不重复下单」的正确性核心）：

1. `ADOPTED`（已采纳未转出）——采购侧看不到，**必须**由本服务计入；
2. `CONVERTED`（已转出）——采购侧建单后**同一批货会同时出现在两边**，
   此时**以采购侧为准**（`putIfAbsent`，不累加）。采购侧数字来自
   `pur_order_item` 实际未收量、会随收货递减；建议单数量是快照、永不变化。
   两者相加会高估在途 → 该补的货被漏补；
3. 采购侧不可用 → 退回「全部建议在途」（含 `CONVERTED`），宁可少补也不要重复下单。

> 这一条是测试抓出来的：初版无差别 `merge` 相加，`convertedDoesNotDoubleCountPurchase`
> 与 `mixedStatuses` 两个用例直接失败。口径错误不会让程序报错，只会让补货量悄悄失真。

**SKU→material 的映射口径**：`prod_sku` 没有独立 material 字段，因此
`materialCode ← skuCode`（如 `SKU-IP15P-128`，采购侧可识别且唯一）、
`materialName ← skuName`（`material_name` NOT NULL）、`unit` 固定「件」。
**这是推断而非主数据契约**——若商品域后续引入真正的 material 主子关系，
替换 `ProductSkuMaterialMappingResolver` 即可，调用方不变。
拿不到映射一律返回 `null` → `convert` 报「待补映射」，绝不用 skuId 冒充 materialCode。

### 11.5 仍未闭环

- 历史回测（设计 §10 的命中率/偏差阈值）尚未建立基线脚本。
- 补货策略的 seed 只覆盖本地种子数据的 6 个 SKU×仓；生产环境需要按实际商品结构维护，否则未覆盖的 SKU 走默认值（交期 7 天 / 复核 7 天 / 安全 3 天 / MOQ 1 / 无供应商）。

> 采购在途 SQL 的租户条件已修：原稿只依赖 `TenantInterceptor` 给驱动表加谓词
> （AGENTS 记录的 JOIN 限制），现在显式补了 `o.tenant_id = i.tenant_id`。

### 11.5.0 虚拟机执行结果（2026-11-14）

在 `192.168.0.121` 的 `scm-postgres` 上实际执行并验收：

| 脚本 | 目标库 | 结果 |
|---|---|---|
| `V2__replenishment.sql` | db_inventory | 4 表 / 17 索引 / 1 外键 / 5 唯一约束，全部建立 |
| `purchase_001_plan_executing_status.sql` | db_purchase | `chk_plan_status` 已放宽到 `(0,1,2,3,4)` |
| `033_replenishment_permissions.sql` | db_permission | 14 条权限码 + 28 条角色授权 |
| `034_replenishment_policy.sql` | db_inventory | 6 条策略 |

**幂等性实测**：三份脚本各复跑一次，`exit=0` 且行数不变（策略仍 6、权限仍 14）。

**约束实测**：非法状态被 `chk_repl_status` 拒绝；重复 `business_key` 被
`uk_repl_business_key` 拒绝；明细外键 `fk_repl_item_head` 生效。

**端到端算量核对**（SKU `...502` / 主仓，真实数据）：
μ=7.21、SS=21.64、ROP=72.14、target=122.64、可用 0 → 触发；
Q_raw=123 → 按 pack=5 取整 **125**（满足 MOQ=5）。与
`ReorderPointCalculator` / `ReplenishmentConstraintChecker` 一致。

### 11.5.0.1 虚拟机暴露的两个真实缺陷（已修）

**1. 需求聚合 SQL 引用了不存在的列（阻断级）**

`InvDemandAggregationMapper` 两条 SQL 都带 `AND deleted = false`，但
`inv_log` 是 append-only 流水表，**没有 `deleted` 列**。真实库上一跑就报
`column "deleted" does not exist` —— 需求聚合从未成功执行过，`inv_demand_forecast`
永远是空表，补货链路在第一步就断了。

单测与 `mvn test` 都发现不了：它们不连库。只有实跑 SQL 才会暴露。已移除该条件。

**2. 策略 seed 的 uuid 类型不匹配**

`INSERT ... SELECT ... FROM (VALUES ...)` 中字符串字面量被推断为 `text`，
目标列是 `uuid`，报 `column "tenant_id" is of type uuid but expression is of type text`。
已改为 `v.tenant::uuid` 等显式 CAST。

> 这两类问题都是「静态检查与单测覆盖不到的运行时契约」，是本轮上 VM 的唯一价值。

**3. 存量脏数据（未修，仅记录）**

`inv_log` 里有 30 条早期测试写入的流水：`business_type` 为空、
`operation_type=2` 但 `quantity` 为正数。白名单口径（`business_type IN
('OUTBOUND','CONFIRM')` + `quantity < 0`）正确地排除了它们，因此不影响正确性，
但会让本地环境的需求聚合返回 0 行。属于既有环境状态，不在交付范围内。

### 11.5.1 补货策略种子（`034_replenishment_policy.sql`）

此前策略表没有 seed，导致：所有 SKU 算量曲线完全一样（全走默认参数），
且每行都带 `NO_SUPPLIER`、`convert` 因缺供应商而无法推进。

新 seed 覆盖 `011_inventory.sql` 的 6 个 SKU×仓，按 ABC 分级拉开差异：

| 分级 | 服务水平 | 交期 | 复核 | 安全天数 | MOQ / 包装 | 典型 |
|---|---|---|---|---|---|---|
| A | 98% | 7–12 天 | 7–21 天 | 3–5 | 5 / 5 | 手机（高价快销） |
| B | 95% | 10 天 | 14 天 | 5 | 3 / 1 | 笔记本 |
| C | 90% | 15 天 | 14 天 | 7 | 20 / 10 | 配件 |

- 供应商取自 `013_supplier.sql` 的固定 UUID（已核对存在）；
- `manual_override=FALSE`：走策略参数，而非台账 `safety_stock` 反推；
- **`ON CONFLICT (tenant_id, sku_id, warehouse_id) DO NOTHING`**：已有策略行
  不会被覆盖——生产策略以人工维护为准，seed 只负责把空表填到可用状态。

### 11.6 OpenAPI 快照（已同步）

`ReplenishmentController` 的 14 个映射与 7 个 schema 已补进
`docs/api/openapi-scm-inventory.json`：

- `py scripts/ci/check_openapi_drift.py` → `RESULT: PASSED`
  （scm-inventory `snapshot=57 mappings, code=57 mappings`，added/missing 均为 0）；
- 全文档 83 个 schema 与 83 处 `$ref` 一一对应，无悬空引用。

两个易踩的点：
1. 漂移校验器用正则扫 `@DeleteMapping` 这类**短注解名**，不认
   `@org.springframework.web.bind.annotation.DeleteMapping` 全限定写法——
   controller 里必须正常 `import` 后使用短名，否则快照会「多出一条」。
2. `sys_permission` 的唯一索引是 `(tenant_id, permission_code)`，一个动作码
   只能挂一条 `api_path`，所以 seed 里同一动作的不同端点用了不同后缀
   （`:policy-get` / `:policy-delete` 等），与快照里 `GET`、`DELETE`
   共用 `/policies/{id}` 是两回事，不要合并。

### 11.7 权限注解（已对齐）

14 个端点全部带 `@PreAuthorize`（与 `033` seed 的权限码逐条对应），写端点
另带 `@AuditLog`。`py scripts/ci/check_permission_annotation_drift.py` →
`errors=0, warnings=21, concerns=0`。

**注解顺序是硬约束**：`@PreAuthorize` 必须写在 `@XxxMapping` **之后**、紧贴方法声明。

原因是校验器用「注解与方法声明之间不得出现 `{};`」判定归属，而
`@PutMapping("/suggestions/{id}/adopt")` 的路径里含 `{id}` 花括号。把
`@PreAuthorize` 放到 mapping **之前**，两者之间就夹进了花括号字符，
该注解被判为「未绑定到任何 request mapping」（CONCERN，10 条）。

正确顺序（与 `InvAlertController` 等存量代码一致）：

```java
@Operation(summary = "...")                 // Swagger 描述
@PutMapping("/suggestions/{id}/adopt")      // 路径（含 {id}）
@PreAuthorize("hasAuthority('...')")        // 权限
@AuditLog(operation = "...", ...)           // 审计（仅写端点）
public ApiResponse<...> adopt(...)          // 方法声明
```

> 反向顺序不会编译失败、也不会被 `mvn test` 发现，只有 CI 的
> `check_permission_annotation_drift.py` 会报 CONCERN。

**豁免端点不得带注解**：`GET /api/v1/pur-order/in-transit` 登记在
`internalSignaturePaths`，因此 `PurOrderController` 里该端点刻意不带
`@PreAuthorize` / `@AuditLog`——带了会被判 ERROR 并破坏服务间调用。