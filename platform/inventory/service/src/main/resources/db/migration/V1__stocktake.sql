-- ======================================================================
-- V1 — 库存盘点（Stocktake）单据与明细
--
-- 背景
--   此前「盘点调整」只是 adjustType=6 的裸数字增减：没有单据、没有冻结快照、
--   没有盘点数量与差异记录，也没有审批，无法回答“谁在什么时候盘了哪些 SKU、
--   差异多少、差异是否被审批通过”。
--
-- 设计
--   1. inv_stocktake        盘点单据头：状态机 DRAFT → SUBMITTED → APPROVED →
--                           POSTED / REJECTED / CANCELLED。
--   2. inv_stocktake_item   盘点明细：开始时冻结账面数（book_*），录入实盘数
--                           (counted_quantity)，差异由服务层计算并持久化，
--                           便于审计复核。
--   3. 差异落账只允许在 POSTED 时通过既有的库存调整路径执行，本表不持有数量
--      真值，避免双写账本。
--
-- 幂等：全部 IF NOT EXISTS；可重复执行；无 DELETE/TRUNCATE。
-- 目标库：db_inventory
-- ======================================================================

CREATE TABLE IF NOT EXISTS inv_stocktake (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    stocktake_no VARCHAR(64) NOT NULL,
    warehouse_id UUID NOT NULL,
    location_code VARCHAR(64),

    -- DRAFT / SUBMITTED / APPROVED / POSTED / REJECTED / CANCELLED
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_stocktake_status CHECK (status IN
            ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'REJECTED', 'CANCELLED')),

    -- 快照冻结时间：明细的账面数取自该时点的台账
    snapshot_at TIMESTAMP NOT NULL DEFAULT NOW(),

    item_count INT NOT NULL DEFAULT 0,
    diff_count INT NOT NULL DEFAULT 0,
    -- 差异汇总（实盘 - 账面），正数盘盈、负数盘亏
    diff_quantity BIGINT NOT NULL DEFAULT 0,

    remark TEXT,
    created_by UUID,
    submitted_by UUID,
    approved_by UUID,
    posted_by UUID,

    submit_time TIMESTAMP,
    approve_time TIMESTAMP,
    post_time TIMESTAMP,

    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uk_stocktake_tenant_no UNIQUE (tenant_id, stocktake_no)
);

CREATE INDEX IF NOT EXISTS idx_stocktake_tenant_status
    ON inv_stocktake(tenant_id, status) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_stocktake_warehouse
    ON inv_stocktake(tenant_id, warehouse_id) WHERE NOT deleted;

COMMENT ON TABLE inv_stocktake IS '库存盘点单据头';
COMMENT ON COLUMN inv_stocktake.status IS '状态：DRAFT/SUBMITTED/APPROVED/POSTED/REJECTED/CANCELLED';
COMMENT ON COLUMN inv_stocktake.diff_quantity IS '差异合计（实盘-账面），正数盘盈、负数盘亏';

CREATE TABLE IF NOT EXISTS inv_stocktake_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    stocktake_id UUID NOT NULL,

    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    location_code VARCHAR(64),

    -- 冻结的账面数：盘点开始时刻的台账快照，后续台账变动不影响本单据
    book_total INT NOT NULL DEFAULT 0,
    book_available INT NOT NULL DEFAULT 0,
    book_locked INT NOT NULL DEFAULT 0,

    -- 实盘数：未录入时为空，录入后才参与差异计算
    counted_quantity INT,

    -- 差异（counted - book_available）；录入后由服务层写入，便于审计与汇总
    diff_quantity INT,

    remark TEXT,

    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_stocktake_item_head FOREIGN KEY (stocktake_id)
        REFERENCES inv_stocktake (id),
    CONSTRAINT uk_stocktake_item_sku UNIQUE (stocktake_id, sku_id, warehouse_id)
);

CREATE INDEX IF NOT EXISTS idx_stocktake_item_head
    ON inv_stocktake_item(stocktake_id) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_stocktake_item_sku
    ON inv_stocktake_item(tenant_id, sku_id, warehouse_id) WHERE NOT deleted;

COMMENT ON TABLE inv_stocktake_item IS '库存盘点明细';
COMMENT ON COLUMN inv_stocktake_item.book_available IS '冻结账面可用量';
COMMENT ON COLUMN inv_stocktake_item.diff_quantity IS '差异 = 实盘 - 账面可用';
