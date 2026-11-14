-- ======================================================================
-- 033 — 智能补货权限码（inventory:replenishment:*）
-- 目标库：db_permission
-- 依赖：002_rbac_permission.sql、019_api_permission_coverage.sql、023_inventory_permissions.sql
--
-- 背景
--   ApiAccessControlFilter 的 unregisteredEndpointPolicy 默认是 DENY：任何没在
--   sys_permission 里登记 (api_path, http_method) 的端点，登录用户访问会直接 403
--   API_NOT_REGISTERED。因此 ReplenishmentController 的端点必须逐个登记，
--   否则整套补货功能"开发完了但打不通"。
--
-- ⚠ 一个权限码只能对应一条 (api_path, http_method)：
--   sys_permission 上有唯一索引
--     idx_permission_code ON (COALESCE(tenant_id,...), permission_code) WHERE NOT deleted
--   （见 migrations/004）。所以「一个动作码覆盖多个端点」是做不到的——
--   后插入的行会把前一行的 api_path 覆盖掉，最终只剩最后一个端点被登记，
--   其余端点仍然 403。这里因此为每个端点各给一个码，用短后缀区分同一动作：
--     :generate / :query / :get / :items / :forecast
--     :edit-item / :adopt / :reject / :cancel / :convert
--     :policy-save / :policy-delete
--
-- 命名与风险级别
--   沿用仓库既有 `inventory:{模块}:{kebab-动作}` 风格。
--   risk_level 1 = 只读、3 = 写、4 = 产生下游单据（转采购计划）。
--   level >= 3 的写操作会被 SysAuditLogServiceImpl 额外镜像进
--   sys_sensitive_operation_log，这是审计要求。
--
-- 路径参数
--   019 的匹配器已升级为模板匹配（{var} 占位符），
--   /suggestions/{id} 这类路径可正常命中，不需要为具体 ID 单独登记。
--
-- 幂等：固定 UUID v5 + ON CONFLICT (permission_code)，可重复执行。
-- ======================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ----------------------------------------------------------------------
-- 1. 权限定义
-- ----------------------------------------------------------------------
INSERT INTO sys_permission (
    id, parent_id, permission_code, permission_name, permission_type,
    api_path, http_method, sort_order, visible, status,
    tenant_id, permission_scope, permission_level, risk_level,
    need_approval, need_two_factor, create_time, update_time, deleted
)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:perm:' || v.code),
       (SELECT p.id FROM sys_permission p WHERE p.permission_code = v.parent LIMIT 1),
       v.code, v.name, 3, v.api, v.method, v.sort_order,
       TRUE, 1,
       '019a0aee-0000-7000-8000-000000000002', 'TENANT', 1, v.risk,
       FALSE, FALSE, NOW(), NOW(), FALSE
FROM (VALUES
    ('inventory:replenishment:generate', '智能补货-触发计算', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/generate', 'POST', 201, 3),
    ('inventory:replenishment:query', '智能补货-建议单列表', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/query', 'POST', 202, 1),
    ('inventory:replenishment:get', '智能补货-建议单详情', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/{id}', 'GET', 203, 1),
    ('inventory:replenishment:items', '智能补货-建议明细', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/{id}/items', 'GET', 204, 1),
    ('inventory:replenishment:forecast', '智能补货-需求预测', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/forecasts/{skuId}', 'GET', 205, 1),
    ('inventory:replenishment:edit-item', '智能补货-人审改量', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/{id}/items/{itemId}', 'PUT', 206, 3),
    ('inventory:replenishment:adopt', '智能补货-采纳', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/{id}/adopt', 'PUT', 207, 3),
    ('inventory:replenishment:reject', '智能补货-驳回', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/{id}/reject', 'PUT', 208, 3),
    ('inventory:replenishment:cancel', '智能补货-取消', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/{id}/cancel', 'PUT', 209, 3),
    ('inventory:replenishment:convert', '智能补货-转采购计划', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/suggestions/{id}/convert', 'POST', 210, 4),
    ('inventory:replenishment:policy-save', '智能补货-策略维护', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/policies', 'POST', 211, 3),
    ('inventory:replenishment:policy-query', '智能补货-策略列表', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/policies/query', 'GET', 212, 1),
    ('inventory:replenishment:policy-get', '智能补货-策略详情', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/policies/{id}', 'GET', 213, 1),
    ('inventory:replenishment:policy-delete', '智能补货-删除策略', 'menu:inventory:alerts',
     '/api/v1/inventory/replenishment/policies/{id}', 'DELETE', 214, 3)
) AS v(code, name, parent, api, method, sort_order, risk)
ON CONFLICT (permission_code) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    permission_name = EXCLUDED.permission_name,
    permission_type = 3,
    api_path = EXCLUDED.api_path,
    http_method = EXCLUDED.http_method,
    sort_order = EXCLUDED.sort_order,
    risk_level = EXCLUDED.risk_level,
    visible = TRUE,
    status = 1,
    tenant_id = EXCLUDED.tenant_id,
    permission_scope = EXCLUDED.permission_scope,
    update_time = NOW(),
    deleted = FALSE;

-- ----------------------------------------------------------------------
-- 2. 授予默认角色
--    ROLE_SUPER_ADMIN / ROLE_TENANT_ADMIN 拿全部补货权限（含 convert，
--    它是租户内采购闭环的一部分）。
-- ----------------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, create_time)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:rp:' || r.role_code || ':' || p.permission_code),
       r.id, p.id, r.tenant_id, NOW()
FROM sys_role r
JOIN sys_permission p
  ON p.tenant_id = '019a0aee-0000-7000-8000-000000000002'
 AND p.status = 1
 AND NOT p.deleted
WHERE r.tenant_id = '019a0aee-0000-7000-8000-000000000002'
  AND r.role_code IN ('ROLE_SUPER_ADMIN', 'ROLE_TENANT_ADMIN')
  AND p.permission_code LIKE 'inventory:replenishment:%'
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
