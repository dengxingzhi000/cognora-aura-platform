-- Agent gateway menu and endpoint permissions. Target database: db_permission.
BEGIN;

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

INSERT INTO sys_permission (
    id, parent_id, permission_code, permission_name, permission_type,
    route_path, icon, sort_order, visible, status,
    tenant_id, permission_scope, permission_level, risk_level,
    need_approval, need_two_factor, create_time, update_time, deleted
)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:perm:menu:agent'),
       NULL, 'menu:agent', 'AI Agent', 2, '/platform-agent', 'bot', 180,
       TRUE, 1,
       '019a0aee-0000-7000-8000-000000000002', 'TENANT', 1, 1,
       FALSE, FALSE, NOW(), NOW(), FALSE
ON CONFLICT (permission_code) DO UPDATE SET
    permission_name = EXCLUDED.permission_name,
    permission_type = EXCLUDED.permission_type,
    route_path = EXCLUDED.route_path,
    icon = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    visible = TRUE,
    status = 1,
    tenant_id = EXCLUDED.tenant_id,
    permission_scope = EXCLUDED.permission_scope,
    update_time = NOW(),
    deleted = FALSE;

INSERT INTO sys_permission (
    id, parent_id, permission_code, permission_name, permission_type,
    route_path, icon, sort_order, visible, status,
    tenant_id, permission_scope, permission_level, risk_level,
    need_approval, need_two_factor, create_time, update_time, deleted
)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:perm:menu:ops'),
       NULL, 'menu:ops', '运维控制台', 2, '/ops', 'deploy', 181,
       TRUE, 1,
       '019a0aee-0000-7000-8000-000000000002', 'TENANT', 1, 1,
       FALSE, FALSE, NOW(), NOW(), FALSE
ON CONFLICT (permission_code) DO UPDATE SET
    permission_name = EXCLUDED.permission_name,
    permission_type = EXCLUDED.permission_type,
    route_path = EXCLUDED.route_path,
    icon = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    visible = TRUE,
    status = 1,
    tenant_id = EXCLUDED.tenant_id,
    permission_scope = EXCLUDED.permission_scope,
    update_time = NOW(),
    deleted = FALSE;

INSERT INTO sys_permission (
    id, parent_id, permission_code, permission_name, permission_type,
    api_path, http_method, sort_order, visible, status,
    tenant_id, permission_scope, permission_level, risk_level,
    need_approval, need_two_factor, create_time, update_time, deleted
)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:perm:' || v.code),
       (SELECT p.id FROM sys_permission p WHERE p.permission_code = 'menu:agent' LIMIT 1),
       v.code, v.name, 3, v.api, v.method, v.sort_order,
       TRUE, 1,
       '019a0aee-0000-7000-8000-000000000002', 'TENANT', 1, v.risk,
       v.risk >= 3, v.risk >= 4, NOW(), NOW(), FALSE
FROM (VALUES
    ('agent:capabilities:view', 'Agent-能力目录', '/api/v1/agent/capabilities', 'GET', 1, 1),
    ('agent:chat:run', 'Agent-执行企业任务', '/api/v1/agent/chat', 'POST', 2, 2),
    ('agent:approval:enterprise-decide', 'Agent-企业任务审批决策', '/api/v1/agent/approvals/{approvalId}/decision', 'POST', 3, 3),
    ('agent:approval:enterprise-resume', 'Agent-企业任务审批恢复', '/api/v1/agent/chat/{taskId}/resume', 'POST', 4, 3),
    ('agent:browser:tasks:list', 'Agent-浏览器任务列表', '/api/v1/agent/browser/tasks', 'GET', 5, 1),
    ('agent:browser:tasks:create', 'Agent-创建浏览器任务', '/api/v1/agent/browser/tasks', 'POST', 6, 3),
    ('agent:browser:tasks:get', 'Agent-浏览器任务详情', '/api/v1/agent/browser/tasks/{taskId}', 'GET', 7, 1),
    ('agent:browser:tasks:steps', 'Agent-浏览器任务步骤', '/api/v1/agent/browser/tasks/{taskId}/steps', 'GET', 8, 1),
    ('agent:browser:approve', 'Agent-批准浏览器步骤', '/api/v1/agent/browser/tasks/{taskId}/approve', 'POST', 9, 3),
    ('agent:browser:reject', 'Agent-驳回浏览器步骤', '/api/v1/agent/browser/tasks/{taskId}/reject', 'POST', 10, 3),
    ('agent:browser:cancel', 'Agent-取消浏览器任务', '/api/v1/agent/browser/tasks/{taskId}/cancel', 'POST', 11, 3),
    ('agent:tasks:list', 'Agent-统一任务列表', '/api/v1/agent/tasks', 'GET', 12, 1),
    ('agent:tasks:get', 'Agent-统一任务详情', '/api/v1/agent/tasks/{taskId}', 'GET', 13, 1),
    ('agent:tasks:decide', 'Agent-统一任务审批决策', '/api/v1/agent/tasks/{taskId}/decision', 'POST', 14, 3),
    ('agent:tasks:resume', 'Agent-统一任务恢复', '/api/v1/agent/tasks/{taskId}/resume', 'POST', 15, 3),
    ('agent:tasks:cancel', 'Agent-统一任务取消', '/api/v1/agent/tasks/{taskId}/cancel', 'POST', 16, 3),
    ('agent:approvals:list', 'Agent-统一审批待办', '/api/v1/agent/approvals', 'GET', 17, 1),
    ('agent:workflows:view', 'Agent-工作流目录', '/api/v1/agent/workflows', 'GET', 18, 1),
    ('agent:workflows:run', 'Agent-运行工作流', '/api/v1/agent/workflows/{name}/runs', 'POST', 19, 2),
    ('agent:knowledge:view', 'Agent-知识文档列表', '/api/v1/agent/knowledge/documents', 'GET', 20, 1),
    ('agent:knowledge:manage', 'Agent-知识文档入库', '/api/v1/agent/knowledge/documents', 'POST', 21, 2),
    ('agent:knowledge:search', 'Agent-知识检索', '/api/v1/agent/knowledge/search', 'GET', 22, 1),
    ('agent:knowledge:delete', 'Agent-知识文档删除', '/api/v1/agent/knowledge/documents/{documentId}/{version}', 'DELETE', 27, 3),
    ('agent:browser:artifacts:list', 'Agent-浏览器产物列表', '/api/v1/agent/browser/tasks/{taskId}/artifacts', 'GET', 23, 1),
    ('agent:browser:artifacts:view', 'Agent-浏览器产物下载', '/api/v1/agent/browser/tasks/{taskId}/artifacts/{artifactId}/content', 'GET', 24, 1),
    ('agent:assistance:predict', 'Agent-AI 介入预测', '/api/v1/agent/assistance/predict', 'POST', 28, 1),
    ('agent:assistance:feedback', 'Agent-AI 介入结果反馈', '/api/v1/agent/assistance/predictions/{predictionId}/feedback', 'POST', 29, 2),
    ('agent:writing:use', 'Agent-AI 写作助手', '/api/v1/agent/writing-assistant', 'POST', 25, 1),
    ('ops:view', '运维-服务状态', '/api/v1/agent/ops/status', 'GET', 26, 1),
    ('ops:catalog:view', '运维-服务目录', '/api/v1/agent/ops/catalog', 'GET', 28, 1),
    ('ops:deploy', '运维-部署操作', '/api/v1/agent/ops/actions', 'POST', 27, 2),
    ('agent:chat:async', 'Agent-异步企业任务', '/api/v1/agent/chat/async', 'POST', 30, 2),
    ('agent:chat:task:get', 'Agent-异步任务状态', '/api/v1/agent/chat/tasks/{taskId}', 'GET', 31, 1),
    ('agent:chat:history', 'Agent-会话历史', '/api/v1/agent/chat/sessions/{sessionId}', 'GET', 32, 1),
    ('agent:workflows:expense:execute', 'Agent-执行报销工作流', '/api/v1/agent/workflow/expense/execute', 'POST', 33, 3),
    ('agent:workflows:expense:resume', 'Agent-恢复报销工作流', '/api/v1/agent/workflow/expense/{threadId}/resume', 'POST', 34, 3),
    ('agent:assistance:model:view', 'Agent-预测模型状态', '/api/v1/agent/assistance/model/status', 'GET', 35, 1),
    ('agent:assistance:dashboard:view', 'Agent-闭环监控看板', '/api/v1/agent/assistance/dashboard/data', 'GET', 36, 1)
) AS v(code, name, api, method, sort_order, risk)
ON CONFLICT (permission_code) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    permission_name = EXCLUDED.permission_name,
    permission_type = 3,
    api_path = EXCLUDED.api_path,
    http_method = EXCLUDED.http_method,
    sort_order = EXCLUDED.sort_order,
    risk_level = EXCLUDED.risk_level,
    need_approval = EXCLUDED.need_approval,
    need_two_factor = EXCLUDED.need_two_factor,
    visible = TRUE,
    status = 1,
    tenant_id = EXCLUDED.tenant_id,
    permission_scope = EXCLUDED.permission_scope,
    update_time = NOW(),
    deleted = FALSE;

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
  AND (p.permission_code IN ('menu:agent', 'menu:ops')
       OR p.permission_code LIKE 'agent:%'
       OR p.permission_code LIKE 'ops:%')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, create_time)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:rp:' || r.role_code || ':' || p.permission_code),
       r.id, p.id, r.tenant_id, NOW()
FROM sys_role r
JOIN sys_permission p
  ON p.tenant_id = '019a0aee-0000-7000-8000-000000000002'
 AND p.permission_code IN ('agent:writing:use', 'ops:view', 'ops:catalog:view', 'menu:ops')
 AND p.status = 1
 AND NOT p.deleted
WHERE r.tenant_id = '019a0aee-0000-7000-8000-000000000002'
  AND r.role_code IN ('ROLE_DEPARTMENT_MANAGER', 'ROLE_OPERATOR')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, create_time)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:rp:' || r.role_code || ':' || p.permission_code),
       r.id, p.id, r.tenant_id, NOW()
FROM sys_role r
JOIN sys_permission p
  ON p.tenant_id = '019a0aee-0000-7000-8000-000000000002'
 AND p.status = 1
 AND NOT p.deleted
WHERE r.tenant_id = '019a0aee-0000-7000-8000-000000000002'
  AND r.role_code = 'ROLE_OPERATOR'
  AND p.permission_code IN ('agent:assistance:predict', 'agent:assistance:feedback')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, create_time)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:rp:' || r.role_code || ':' || p.permission_code),
       r.id, p.id, r.tenant_id, NOW()
FROM sys_role r
JOIN sys_permission p
  ON p.tenant_id = '019a0aee-0000-7000-8000-000000000002'
 AND p.status = 1
 AND NOT p.deleted
WHERE r.tenant_id = '019a0aee-0000-7000-8000-000000000002'
  AND r.role_code = 'ROLE_OPERATOR'
  AND p.permission_code IN ('agent:chat:async', 'agent:chat:task:get', 'agent:chat:history',
                            'agent:knowledge:view', 'agent:knowledge:search',
                            'agent:approval:enterprise-resume',
                            'agent:workflows:view', 'agent:workflows:run')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, create_time)
SELECT uuid_generate_v5(uuid_ns_url(), 'scm:rp:' || r.role_code || ':' || p.permission_code),
       r.id, p.id, r.tenant_id, NOW()
FROM sys_role r
JOIN sys_permission p
  ON p.tenant_id = '019a0aee-0000-7000-8000-000000000002'
 AND p.status = 1
 AND NOT p.deleted
WHERE r.tenant_id = '019a0aee-0000-7000-8000-000000000002'
  AND r.role_code = 'ROLE_DEPARTMENT_MANAGER'
  AND p.permission_code IN ('agent:chat:async', 'agent:chat:task:get', 'agent:chat:history',
                            'agent:knowledge:manage', 'agent:approval:enterprise-decide',
                            'agent:approval:enterprise-resume', 'agent:workflows:expense:execute',
                            'agent:workflows:expense:resume')
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
