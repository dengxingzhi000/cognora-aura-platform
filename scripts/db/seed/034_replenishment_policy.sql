-- ======================================================================
-- 034 — 智能补货策略种子数据（inv_replenishment_policy）
-- 目标库：db_inventory
--
-- 为什么需要这份 seed
--   补货计算的入参全部来自 inv_replenishment_policy：
--     交期 L、复核周期、安全库存天数、MOQ、包装倍数、供应商。
--   没有策略行时 ReplenishmentComputeService 会退回硬编码默认值
--   （L=7 / review=7 / safetyDays=3 / MOQ=1 / pack=1、无供应商），
--   结果所有 SKU 的算量曲线完全一样，且每行都带 NO_SUPPLIER 标记，
--   补货建议无法转采购计划（映射与供应商都缺失）。
--
-- 口径
--   与 011_inventory.sql 的 SKU×仓 组合一一对应（6 行）。
--   ABC 分级按「单价×周转」的商业常识给初值，仅供本地开发与非生产环境：
--     A = 高价快销（手机）→ 服务水平 98%、交期短、MOQ 小
--     B = 中价（笔记本）  → 95%
--     C = 配件（耳机等）  → 90%、交期长、MOQ 大
--   供应商 ID 取自 013_supplier.sql 的固定 UUID。
--
-- ⚠ 必须显式 CAST：VALUES 里的字符串字面量会被推断成 text，而目标列是 uuid。
--   不 CAST 时报 `column "tenant_id" is of type uuid but expression is of type text`。
--   这个问题只有在真实 PostgreSQL 上才会暴露，单元测试发现不了。
--
-- 幂等：固定 UUID v5 + ON CONFLICT (tenant_id, sku_id, warehouse_id) DO NOTHING。
--       已有策略行不会被覆盖——生产环境的策略以人工维护为准。
-- ======================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

INSERT INTO inv_replenishment_policy (
    id, tenant_id, sku_id, warehouse_id,
    abc_class, service_level, lead_time_days, review_period_days, safety_days,
    min_order_qty, pack_multiple, supplier_id,
    manual_override, enabled, remark,
    create_time, update_time, deleted
)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:repl-policy:' || v.tenant || ':' || v.sku || ':' || v.wh),
       v.tenant::uuid, v.sku::uuid, v.wh::uuid,
       v.abc, v.svc, v.lead, v.review, v.safety,
       v.moq, v.pack, v.supplier::uuid,
       FALSE, TRUE, v.remark,
       NOW(), NOW(), FALSE
FROM (VALUES
    -- iPhone 15 Pro 128G @ 主仓：A 类，短交期
    ('019a0aee-0000-7000-8000-000000000002', '019a0aee-0000-7000-8000-000000000501',
     '019a0aee-0000-7000-8000-000000000601',
     'A', 0.9800, 7,  7,  3.00, 5,  5,
     '019a0aee-0000-7000-8000-000000000701', '本地种子：A 类快销，短交期小批量'),
    -- iPhone 15 Pro 256G @ 主仓
    ('019a0aee-0000-7000-8000-000000000002', '019a0aee-0000-7000-8000-000000000502',
     '019a0aee-0000-7000-8000-000000000601',
     'A', 0.9800, 7,  7,  3.00, 5,  5,
     '019a0aee-0000-7000-8000-000000000701', '本地种子：A 类快销'),
    -- MacBook Air M3 256G @ 主仓：B 类，交期中等
    ('019a0aee-0000-7000-8000-000000000002', '019a0aee-0000-7000-8000-000000000503',
     '019a0aee-0000-7000-8000-000000000601',
     'B', 0.9500, 10, 14, 5.00, 3,  1,
     '019a0aee-0000-7000-8000-000000000702', '本地种子：B 类，交期 10 天'),
    -- Galaxy S24 Ultra 512G @ 华东仓：A 类，但该仓销量低 → 复核周期长
    ('019a0aee-0000-7000-8000-000000000002', '019a0aee-0000-7000-8000-000000000504',
     '019a0aee-0000-7000-8000-000000000602',
     'A', 0.9800, 12, 21, 5.00, 5,  5,
     '019a0aee-0000-7000-8000-000000000702', '本地种子：A 类但低周转仓，复核 21 天'),
    -- AirPods Pro 2 @ 主仓：C 类，长交期大批量
    ('019a0aee-0000-7000-8000-000000000002', '019a0aee-0000-7000-8000-000000000505',
     '019a0aee-0000-7000-8000-000000000601',
     'C', 0.9000, 15, 14, 7.00, 20, 10,
     '019a0aee-0000-7000-8000-000000000704', '本地种子：C 类配件，MOQ 20/包装 10'),
    -- 小米 14 Ultra 512G @ 华南仓：B 类
    ('019a0aee-0000-7000-8000-000000000002', '019a0aee-0000-7000-8000-000000000506',
     '019a0aee-0000-7000-8000-000000000603',
     'B', 0.9500, 10, 14, 5.00, 3,  1,
     '019a0aee-0000-7000-8000-000000000705', '本地种子：B 类，华南仓')
) AS v(tenant, sku, wh, abc, svc, lead, review, safety, moq, pack, supplier, remark)
ON CONFLICT (tenant_id, sku_id, warehouse_id) DO NOTHING;

COMMIT;
