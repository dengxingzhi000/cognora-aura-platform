-- ======================================================================
-- V2 — 库存智能补货链：策略 / 需求预测 / 补货建议单
--
-- 背景
--   现状是「静态告警 → 人工拍脑袋建采购计划」：InvAlertCommandService 只比
--   available_stock <= safety_stock，既不看需求，也不算在途，因此同一 SKU 会被
--   反复补货；采购计划是纯手工 CRUD，没有任何需求依据。
--
-- 设计（对应 docs/design/inventory-replenishment-design.md §5）
--   1. inv_replenishment_policy           SKU×仓的补货参数（交期、复核周期、服务水平…）
--   2. inv_demand_forecast                每 SKU×仓×日的需求预测 μ/σ/mape
--   3. inv_replenishment_suggestion       补货建议单头（状态机 + 幂等 business_key）
--   4. inv_replenishment_suggestion_item  建议明细（含决策快照 reason_snapshot）
--
-- 归属说明
--   设计稿 §5.2 里还列了 supplier_sku_perf，但它属于供应商域（db_supplier）：
--   放在 db_inventory 会让库存服务跨库读供应商，违反 CQRS 边界。本迁移不建该表。
--
-- 幂等：全部 IF NOT EXISTS / ADD COLUMN IF NOT EXISTS；可重复执行；无 DELETE/TRUNCATE。
-- 目标库：db_inventory（per-service Flyway，baseline-version=0 会真正执行本文件）
-- ======================================================================

-- ----------------------------------------------------------------------
-- 1. 补货策略（SKU × 仓）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inv_replenishment_policy (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,

    -- ABC 分级：决定默认服务水平（A=98% B=95% C=90%），MVP 由人工填写
    abc_class VARCHAR(1) NOT NULL DEFAULT 'C'
        CONSTRAINT chk_repl_abc CHECK (abc_class IN ('A', 'B', 'C')),
    -- 目标服务水平，MVP 只做展示与统计版公式入参；规则版用 safety_days 近似
    service_level NUMERIC(5,4) NOT NULL DEFAULT 0.9500
        CONSTRAINT chk_repl_service_level CHECK (service_level > 0 AND service_level <= 1),
    -- 供应交期 L（天）：MVP 人工填，统计版从 supplier_sku_perf 取
    lead_time_days INT NOT NULL DEFAULT 7
        CONSTRAINT chk_repl_lead_time CHECK (lead_time_days >= 0),
    -- 补货复核周期（天）：与 L 一起决定目标水位
    review_period_days INT NOT NULL DEFAULT 7
        CONSTRAINT chk_repl_review_period CHECK (review_period_days >= 0),
    -- 规则版安全库存近似：SS ≈ μ × safety_days
    safety_days NUMERIC(8,2) NOT NULL DEFAULT 3
        CONSTRAINT chk_repl_safety_days CHECK (safety_days >= 0),
    -- 供应商 MOQ 与包装倍数：算量后向上取整用
    min_order_qty INT NOT NULL DEFAULT 1
        CONSTRAINT chk_repl_moq CHECK (min_order_qty >= 1),
    pack_multiple INT NOT NULL DEFAULT 1
        CONSTRAINT chk_repl_pack CHECK (pack_multiple >= 1),
    -- 单一供应商（MVP）：选源打分留到统计版
    supplier_id UUID,

    -- 人工覆盖开关：true 时以 inv_inventory.safety_stock/max_stock 为准，
    -- 计算出的 SS/目标水位只作为参考值展示
    manual_override BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    remark TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uk_repl_policy UNIQUE (tenant_id, sku_id, warehouse_id)
);

CREATE INDEX IF NOT EXISTS idx_repl_policy_sku
    ON inv_replenishment_policy(tenant_id, sku_id, warehouse_id) WHERE NOT deleted;

COMMENT ON TABLE inv_replenishment_policy IS 'SKU×仓补货策略（交期/复核周期/ABC/MOQ/包装倍数）';
COMMENT ON COLUMN inv_replenishment_policy.lead_time_days IS '供应交期 L（天），规则版人工填写';
COMMENT ON COLUMN inv_replenishment_policy.safety_days IS '规则版安全库存天数，SS ≈ μ × safety_days';
COMMENT ON COLUMN inv_replenishment_policy.manual_override IS 'true 时以台账 safety_stock/max_stock 为准';

-- ----------------------------------------------------------------------
-- 2. 需求预测（SKU × 仓 × 日 × 版本）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inv_demand_forecast (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    forecast_date DATE NOT NULL,

    -- 预测日需求 μ 与需求标准差 σ
    daily_demand NUMERIC(18,4) NOT NULL
        CONSTRAINT chk_forecast_demand CHECK (daily_demand >= 0),
    demand_std_dev NUMERIC(18,4) NOT NULL DEFAULT 0
        CONSTRAINT chk_forecast_std CHECK (demand_std_dev >= 0),
    -- 回测误差，规则版为 NULL（移动平均没有可靠 MAPE）
    mape NUMERIC(6,4),

    -- 方法：WEIGHTED_MOVING_AVERAGE / HOLT_WINTERS / PROPHET / GBDT
    method VARCHAR(32) NOT NULL,
    -- NORMAL / LOW_CONFIDENCE（冷启动/样本不足）
    confidence VARCHAR(16) NOT NULL DEFAULT 'NORMAL'
        CONSTRAINT chk_forecast_confidence CHECK (confidence IN ('NORMAL', 'LOW_CONFIDENCE')),
    -- 参与计算的历史天数与样本量，供人审判断可信度
    sample_days INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,

    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uk_demand_forecast UNIQUE (tenant_id, sku_id, warehouse_id, forecast_date, version)
);

CREATE INDEX IF NOT EXISTS idx_demand_forecast_sku
    ON inv_demand_forecast(tenant_id, sku_id, warehouse_id) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_demand_forecast_date
    ON inv_demand_forecast(tenant_id, forecast_date) WHERE NOT deleted;

COMMENT ON TABLE inv_demand_forecast IS 'SKU×仓日需求预测（μ/σ/mape/方法/版本）';
COMMENT ON COLUMN inv_demand_forecast.confidence IS 'NORMAL / LOW_CONFIDENCE（冷启动或样本不足）';

-- ----------------------------------------------------------------------
-- 3. 补货建议单头
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inv_replenishment_suggestion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    suggestion_no VARCHAR(64) NOT NULL,
    warehouse_id UUID,
    -- 补货周期，如 2026-W45
    period VARCHAR(32) NOT NULL,

    -- DRAFT → ADOPTED → CONVERTED；DRAFT → REJECTED / CANCELLED；ADOPTED → CANCELLED
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_repl_status CHECK (status IN
            ('DRAFT', 'ADOPTED', 'CONVERTED', 'REJECTED', 'CANCELLED')),

    -- 幂等键：tenant + warehouse + period。unique 约束挡同周期重复生成
    business_key VARCHAR(128) NOT NULL,

    item_count INT NOT NULL DEFAULT 0,
    total_suggested_qty BIGINT NOT NULL DEFAULT 0,
    total_adopted_qty BIGINT NOT NULL DEFAULT 0,

    -- 转采购计划后的回写；转换失败时留空并写 convert_error
    pur_plan_id UUID,
    pur_plan_no VARCHAR(128),
    convert_error TEXT,

    -- 人审留痕
    reject_reason TEXT,
    remark TEXT,
    created_by UUID,
    adopted_by UUID,
    adopted_at TIMESTAMP,
    converted_at TIMESTAMP,

    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uk_repl_suggestion_no UNIQUE (tenant_id, suggestion_no),
    CONSTRAINT uk_repl_business_key UNIQUE (tenant_id, business_key)
);

CREATE INDEX IF NOT EXISTS idx_repl_suggestion_status
    ON inv_replenishment_suggestion(tenant_id, status) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_repl_suggestion_period
    ON inv_replenishment_suggestion(tenant_id, period) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_repl_suggestion_warehouse
    ON inv_replenishment_suggestion(tenant_id, warehouse_id) WHERE NOT deleted;

COMMENT ON TABLE inv_replenishment_suggestion IS '补货建议单头（人在环：草稿→采纳/驳回→转采购计划）';
COMMENT ON COLUMN inv_replenishment_suggestion.business_key IS '幂等键 tenant+warehouse+period，防同周期重复生成';
COMMENT ON COLUMN inv_replenishment_suggestion.status IS 'DRAFT/ADOPTED/CONVERTED/REJECTED/CANCELLED';

-- ----------------------------------------------------------------------
-- 4. 补货建议明细
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inv_replenishment_suggestion_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    suggestion_id UUID NOT NULL,

    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,

    -- 计算时刻的快照：可用量、在途量、ROP、SS、目标水位
    available_stock INT NOT NULL DEFAULT 0,
    in_transit_qty INT NOT NULL DEFAULT 0,
    reorder_point NUMERIC(18,4) NOT NULL DEFAULT 0,
    safety_stock NUMERIC(18,4) NOT NULL DEFAULT 0,
    target_stock NUMERIC(18,4) NOT NULL DEFAULT 0,

    -- 模型建议量与人工采纳量（改量后 adopted_qty 与 suggested_qty 不同）
    suggested_qty INT NOT NULL
        CONSTRAINT chk_repl_item_suggested CHECK (suggested_qty >= 0),
    adopted_qty INT,

    -- MVP 单一供应商；统计版由 decision-engine 打分选出
    supplier_id UUID,
    -- 人审提示：NEEDS_MANUAL / NO_LEAD_TIME / NO_SUPPLIER / MISSING_MATERIAL_MAPPING
    flags VARCHAR(128),
    -- 决策快照：命中规则、裁剪原因、公式入参，供人审解释
    reason_snapshot TEXT,

    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_repl_item_head FOREIGN KEY (suggestion_id)
        REFERENCES inv_replenishment_suggestion (id),
    CONSTRAINT uk_repl_item_sku UNIQUE (suggestion_id, sku_id, warehouse_id)
);

CREATE INDEX IF NOT EXISTS idx_repl_item_head
    ON inv_replenishment_suggestion_item(suggestion_id) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_repl_item_sku
    ON inv_replenishment_suggestion_item(tenant_id, sku_id, warehouse_id) WHERE NOT deleted;

COMMENT ON TABLE inv_replenishment_suggestion_item IS '补货建议明细（含决策快照，供人审解释）';
COMMENT ON COLUMN inv_replenishment_suggestion_item.flags IS 'NEEDS_MANUAL/NO_LEAD_TIME/NO_SUPPLIER/MISSING_MATERIAL_MAPPING';
COMMENT ON COLUMN inv_replenishment_suggestion_item.reason_snapshot IS '决策快照 JSON：公式入参、命中约束、裁剪原因';
