-- ======================================================================
-- 采购批次 1：放宽 pur_plan.status 约束，容纳「执行中」
--
-- 问题
--   018_db_purchase.sql 把 pur_plan.status 钉死为 CHECK (status IN (0,1,2,3))，
--   而 PurPlanCommandService 的「开始执行」此前是 APPROVED→APPROVED 的空操作——
--   状态 2 被当作「已审批」，可「执行中」没有独立编码可写。
--
--   本迁移把 4 定义为 EXECUTING，配合 PurPlanStatus 状态机让
--   APPROVED(2) → EXECUTING(4) → COMPLETED(3) 成为一条真实的两步流转。
--
-- 口径（与 PurPlanStatus 一致）
--   0 = 编制中（DRAFT）
--   1 = 待审批（PENDING_APPROVAL）
--   2 = 已审批，可执行（APPROVED）
--   3 = 已完成（COMPLETED，终态）
--   4 = 执行中（EXECUTING）
--
-- 兼容性
--   只放宽约束、不写数据：既有 status=3 的行在新口径里是「已完成」，
--   而修复前 complete() 的注释称其为 REJECTED——那次落库本身就是 bug，
--   不做数据迁移，避免把真实已完成/被误置的计划混为一谈。
--
-- 幂等：约束先 DROP IF EXISTS 再 ADD，可重复执行；无 DELETE/TRUNCATE。
-- 执行：psql -d db_purchase -f purchase_001_plan_executing_status.sql
-- ======================================================================

BEGIN;

ALTER TABLE pur_plan DROP CONSTRAINT IF EXISTS chk_plan_status;

ALTER TABLE pur_plan
    ADD CONSTRAINT chk_plan_status CHECK (status IN (0, 1, 2, 3, 4));

COMMENT ON COLUMN pur_plan.status IS
    '状态:0-编制中,1-待审批,2-已审批(可执行),3-已完成,4-执行中';

COMMIT;
