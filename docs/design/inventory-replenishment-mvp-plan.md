# 库存智能补货链 — MVP 实现规划（规则版）

> 版本：v0.1
> 日期：2026-11-10
> 配套设计：`inventory-replenishment-design.md`
> 目标：交付「移动平均需求 + ROP(含在途) + 目标水位补货量 + 单供应商 + 补货建议单草稿→人审→转采购计划」一条可回测、可解释、幂等的闭环。**不含**动态 σ 安全库存、decision-engine 选源打分、ML、多仓分配（留统计版）。

---

## 0. MVP 边界（严格收敛）

| 纳入 MVP | 留到统计版 |
|---|---|
| 加权移动平均日需求 μ | σ / Holt-Winters / MAPE 报表 |
| ROP = μ×L + SS，SS 用固定服务天数近似 | 动态 SS = Z×sqrt(...) |
| 在途量纳入触发判定 | ABC 自动分级（MVP 用 policy 手工填） |
| 目标水位补货量 + MOQ/仓容/预算约束 | decision-engine 供应商打分（MVP 用 policy 指定单一供应商） |
| 建议单状态机 + 人审改量/采纳/驳回 | 多仓分配 |
| 转 PurPlan（事件+outbox，幂等） | 反馈闭环学习 |
| 历史回测测试 | 自动下单（永不纳入，保持人审） |

---

## 1. 前置修复（动手第一步，阻塞项）

1. **修 PurPlan 状态机 bug**（`scm/purchase/.../service/command/PurPlanCommandService.java`）：
   - `complete():82` 误走 `APPROVED→REJECTED` 且置 status=3；改为正确的完成态流转。
   - `startExecution():72` 是 `APPROVED→APPROVED` 空操作；补真实执行态。
   - 配套单测断言状态流转。
2. **核实 inv_log 出库口径**：确认销售出库与调拨/报损可区分（按 businessType/operationType 过滤），否则需求预测虚高。产出：需求聚合只取销售出库的过滤条件。
3. **核实 SKU→material 映射**：PurPlanItem 用 `materialCode`，建议单用 `skuId`；确认商品主数据映射来源，缺失则 convert 步骤标「待补映射」。

---

## 2. 模块落点与类清单

### 2.1 platform-inventory（主体）
```
domain/entity/
  InvReplenishmentPolicy.java          新增 @TableName inv_replenishment_policy
  InvDemandForecast.java               新增
  InvReplenishmentSuggestion.java      新增（头）
  InvReplenishmentSuggestionItem.java  新增（明细）
domain/
  ReplenishmentStatus.java             新增枚举 + canTransitionTo()（仿 StocktakeStatus）
mapper/
  InvReplenishmentPolicyMapper.java    新增 BaseMapper
  InvDemandForecastMapper.java         新增
  InvReplenishmentSuggestionMapper.java新增
  InvReplenishmentSuggestionItemMapper.java 新增
dto/
  ReplenishmentGenerateRequest.java / SuggestionResponse.java / SuggestionItemResponse.java
  SuggestionQueryRequest.java / AdoptRequest.java / RejectRequest.java / ItemAdjustRequest.java
  ReplenishmentPolicyRequest.java
service/query/
  DemandAggregationQueryService.java   新增 @Slave；从 inv_log 聚合日出库 → μ（加权移动平均）
  ReplenishmentSuggestionQueryService.java 新增 @Slave；分页/详情
service/command/
  ReplenishmentComputeService.java     新增 @Master；编排：读预测→算ROP→算量→约束→建请单(DRAFT)
  ReplenishmentSuggestionCommandService.java 新增；adopt/reject/adjust/convert 状态流转
  ReplenishmentPolicyCommandService.java 新增
service/replenishment/
  ReorderPointCalculator.java          新增 纯函数：ROP/SS(近似)/targetStock/Q，可单测
  ReplenishmentConstraintChecker.java  新增 MOQ/仓容/预算裁剪，返回可行量+原因
  InTransitQuantityResolver.java       新增 查 PurOrder 未收货量（Dubbo/REST，只读）
controller/
  ReplenishmentController.java         新增 §设计§6 接口
job/
  ReplenishmentComputeJobHandler.java  新增 XXL-Job，仿 InventoryAlertScanJobHandler；按租户循环
event/
  ReplenishmentConvertedEvent.java     新增 outbox 事件（转采购计划）
```

### 2.2 scm-purchase（承接）
```
service/command/PurPlanCommandService.java   修 bug + 新增 createFromReplenishment(dto)
listener/ or consumer/
  ReplenishmentConvertedConsumer.java        新增 消费 outbox 事件 → 建 PurPlan(DRAFT)+items
api/PurchaseDubboService.java                核实/新增 未收货在途量查询方法（供 inventory 只读）
```

### 2.3 复用（不重写）
- `decision-engine`：MVP 只用 `ConstraintChain`/`SpelConstraint` 做量约束（选源打分留统计版）。
- outbox 基建、`TenantContextHolder.executeInTenantContext`、`@Master/@Slave`、`ApiResponse`、`StatusValidator`。

---

## 3. 数据库迁移

- 新增 `platform/inventory/service/src/main/resources/db/migration/V2__replenishment.sql`（DDL 见设计 §5.2，MVP 先建 policy/forecast/suggestion/suggestion_item 四表；`supplier_sku_perf` 留统计版并定位到 db_supplier）。
- starter 已在（`platform/inventory/service/pom.xml` 已引 spring-boot-starter-flyway，过父 POM antrun 门禁）。
- 全部 IF NOT EXISTS + tenant_id NOT NULL；实体 tenant_id 走 `FieldFill.INSERT`。

---

## 4. 事件 / Job / 幂等设计

- **计算触发**：XXL-Job 定时（复核周期）+ HTTP 手动 `/suggestions/generate`。Job 无租户上下文 → 遍历租户 `executeInTenantContext`。
- **幂等键**：`business_key = tenant + warehouse + period`（period 如 `2026-W45`）；unique 约束挡重复生成，重复触发返回已有 DRAFT。
- **在途去重**：触发判定 `available + inTransit ≤ ROP`；已生成未收货的量算进 inTransit，防同周期重复补。
- **转采购**：`convert` 只发 `ReplenishmentConvertedEvent` 进 outbox（同事务），purchase 侧消费建 PurPlan；消费幂等键 = suggestion_no。建议单 `ADOPTED→CONVERTED` 与事件写入同事务，回写 pur_plan_id。
- **失败**：purchase 消费失败 → 建议单停留 ADOPTED + 标「转换失败」，可重试；不回滚已采纳决策。

---

## 5. 计算规则（MVP 近似，纯函数可单测）

```
μ  = 加权移动平均(近 N 天销售出库)           // DemandAggregationQueryService
L  = policy.leadTimeDays (MVP 手工填, 统计版从 supplier 绩效取)
SS ≈ μ × safetyDays                        // MVP 近似; 统计版换 Z×sqrt(...)
ROP = μ × L + SS
targetStock = μ × (L + reviewPeriodDays) + SS
Q_raw = targetStock − availableStock − inTransitQty
触发条件: availableStock + inTransitQty ≤ ROP 且 Q_raw > 0
Q = 约束裁剪(Q_raw): 向上取 MOQ/包装倍数, 向下受 maxStock−现有 / 预算 限制
```
- `ReorderPointCalculator` 与 `ReplenishmentConstraintChecker` 为纯函数，输入 DTO 输出结果+原因，不碰 DB，便于回测与单测。

---

## 6. 测试用例清单

### 单元（纯函数，重点）
- `ReorderPointCalculatorTest`：ROP/targetStock/Q 各分支；在途抬高后不触发；Q_raw≤0 不补。
- `ReplenishmentConstraintCheckerTest`：MOQ 向上取整、包装倍数、仓容上限裁剪、预算裁剪、冲突给可行量+原因。
- `DemandAggregationQueryServiceTest`：加权移动平均；扣除非销售出库；冷启动回退 + LOW_CONFIDENCE。
- `ReplenishmentStatusTest`：全状态流转矩阵（仿 StocktakeStatusTest），终态不可再转。

### 命令/编排（Mockito）
- `ReplenishmentComputeServiceTest`：business_key 幂等（重复生成返回既有）；在途去重不重复下单；无 lead time/无供应商 → 标需人工。
- `ReplenishmentSuggestionCommandServiceTest`：仅 DRAFT 可改量/采纳；仅 ADOPTED 可 convert；convert 发 outbox 事件 + 状态同事务；驳回带原因。
- `PurPlanCommandServiceTest`：修 bug 后 complete/startExecution 正确流转；createFromReplenishment 建 DRAFT+items。

### 集成 / 回测（关键验收）
- **历史回测**：对一段历史出库+库存区间跑计算，断言缺货触发命中率、建议量与人工实际采购偏差 ≤ 约定阈值（阈值先设保守值，评审可调）。
- **在途去重集成**：同周期已有未收货 PurOrder → 再次生成不重复补该 SKU。
- **转换幂等集成**：重复消费 ReplenishmentConvertedEvent 只建一张 PurPlan。
- **租户隔离**：Job 跨租户循环不串数据（仿现有租户测试）。

> 集成测试若需 Spring 上下文/DB，遵循 AGENTS：Testcontainers 2.x + `@Tag("integration")`（默认跳过，按需 profile 跑）。MVP 优先把计算逻辑做成纯函数单测，降低对容器的依赖。

---

## 7. 交付顺序（建议提交批次，按功能分层）

1. **批次 A（前置修复）**：PurPlan bug 修复 + 状态机测试。（独立可合并）
2. **批次 B（数据层）**：V2 迁移 + 4 实体 + mapper + ReplenishmentStatus + 状态机测试。
3. **批次 C（计算核心）**：ReorderPointCalculator / ConstraintChecker / DemandAggregation + 纯函数单测 + 回测。
4. **批次 D（编排+接口）**：ComputeService / SuggestionCommandService / Controller / 权限 seed + 命令测试。
5. **批次 E（跨服务闭环）**：outbox 事件 + purchase 消费 + createFromReplenishment + 幂等/转换集成测试。
6. **批次 F（Job+策略维护）**：XXL-Job handler + policy 接口 + 租户循环测试。

> 每批次自带测试、可独立 CI 绿；大迁移 SQL 单独成提交（对齐 GitHub Flow 分层提交偏好）。

---

## 8. 验收门禁（MVP 完成的定义）

- [x] 前置 PurPlan bug 修复 + 测试绿。
      `PurPlanStatus`（0编制中/1待审批/2已审批/3已完成/4执行中）+
      `PurPlanStatusTest`(7) + `PurPlanCommandServiceTest`(10)；
      `complete()` 走 `EXECUTING(4)→COMPLETED(3)`，`startExecution()` 走 `APPROVED(2)→EXECUTING(4)`，
      不再假借 `APPROVED→REJECTED` 校验、不再同状态空转。
- [x] 计算纯函数单测 + 历史回测达阈值。
      `ReorderPointCalculatorTest`(10) / `ReplenishmentConstraintCheckerTest`(14) /
      `WeightedMovingAverageTest`(13) / `ReplenishmentBacktestTest`(4)。
      回测阈值取保守值并提成常量：偏差 ≤ 55%、缺货触发命中率 ≥ 60%（见 §9.3 待业务评审）。
      回测同时钉住「需求口径只认销售出库」：混入调拨/报损会让 μ 与建议量成倍膨胀。
- [x] 「在途去重不重复下单」集成测试绿。
      `ReplenishmentComputeServiceTest#inTransitPreventsDuplicateSuggestion`
      + `ReplenishmentBacktestTest#backtestInTransitPreventsDuplicateOrdering`。
- [x] 建议单状态机全矩阵测试绿。
      `ReplenishmentStatusTest`(5)：DRAFT→ADOPTED/REJECTED/CANCELLED、
      ADOPTED→CONVERTED/CANCELLED、三终态、`from()` 拒绝未知值。
- [x] convert 转换幂等集成测试绿。
      `ReplenishmentConvertedConsumerTest`(7) 覆盖重复消费只建一张计划（按 plan_no 幂等）
      + `ReplenishmentComputeServiceTest#generateIsIdempotentPerBusinessKey`。
- [x] 新接口全部登记权限目录（否则默认 DENY 打不通）。
      `scripts/db/seed/033_replenishment_permissions.sql`：14 个端点逐条登记
      （一个权限码只能对一条 (api_path, http_method)，故按端点拆码）；
      已接入 `seed-all.sh` / `seed-all.bat`。
      14 个端点同时补齐 `@PreAuthorize` + 写端点 `@AuditLog`
      （`check_permission_annotation_drift.py`：hasAuthority 653 → 667，errors=0）。
- [x] 租户隔离测试绿。
      `ReplenishmentComputeJobTenantIsolationTest`(4)：Job 按租户循环、
      每次调用都在对应租户上下文内、失败租户不污染其它租户、结束不残留上下文。
- [x] 定向 `mvn test -pl ../platform/inventory/service,../scm/purchase/service -am -f com.scm.parent/pom.xml` 通过。

> 说明：现状的集成级验收用 Mockito 单测 + 纯函数回测覆盖，未启用 Testcontainers。
> 原因：`platform/common/core` 的 `AbstractIntegrationTest` 依赖的
> `db/init-test.sql` 在仓库里不存在，容器起不来。待该 init 脚本补齐后，
> 可把上述用例平移到 `@Tag("integration")` 下做真库验证（父 POM 默认跳过该 tag）。

---

## 9. 未决项

1. `supplier_sku_perf` 归属（db_supplier vs db_inventory）——**已定**：归 db_supplier，
   放 inventory 会让库存服务跨库读供应商、违反 CQRS 边界。`V2__replenishment.sql` 因此不建该表。
2. SKU→material 映射来源（商品主数据）——convert 依赖。**现状**：映射缺失时不发事件，
   建议单标 `MISSING_MATERIAL_MAPPING` 并保持 ADOPTED 可重试，不静默丢单。
3. 回测偏差阈值 / 服务天数 `safetyDays` 默认值——**暂定**偏差 ≤ 55%、命中率 ≥ 60%、
   `safetyDays=3`，均提成常量待业务评审（`ReplenishmentBacktestTest` 与
   `ReplenishmentComputeService.DEFAULT_SAFETY_DAYS`）。
4. L（交期）MVP 手工填 vs 从 supplier 历史取——**已定**：MVP 读
   `inv_replenishment_policy.lead_time_days`，缺策略时回落 7 天并标 `NO_LEAD_TIME` + `NEEDS_MANUAL`；
   统计版从供应商绩效取。

> 维护：本规划随实现推进更新；类名/路径为 proposed，落地后以实际代码为准。