CREATE TABLE IF NOT EXISTS platform_agent_task (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    user_id             UUID NOT NULL,
    agent_type          VARCHAR(24) NOT NULL,
    external_task_id    VARCHAR(128) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    title               VARCHAR(256) NOT NULL,
    summary             TEXT,
    approval_id         VARCHAR(128),
    payload             JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_agent_task_external UNIQUE (tenant_id, agent_type, external_task_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_agent_task_tenant_created
    ON platform_agent_task (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_platform_agent_task_tenant_status
    ON platform_agent_task (tenant_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_platform_agent_task_approvals
    ON platform_agent_task (tenant_id, updated_at DESC)
    WHERE status = 'WAITING_APPROVAL' AND approval_id IS NOT NULL;
