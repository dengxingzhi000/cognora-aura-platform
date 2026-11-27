package com.cognora.platform.agent.task;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskStoreTest {

    @Test
    void persistsTenantScopedTasksAndApprovals() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-task;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE platform_agent_task (
                    id UUID PRIMARY KEY,
                    tenant_id UUID NOT NULL,
                    user_id UUID NOT NULL,
                    agent_type VARCHAR(24) NOT NULL,
                    external_task_id VARCHAR(128) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    title VARCHAR(256) NOT NULL,
                    summary VARCHAR(1000),
                    approval_id VARCHAR(128),
                    payload JSONB NOT NULL DEFAULT '{}'::JSONB,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                    CONSTRAINT uq_platform_agent_task_external UNIQUE (tenant_id, agent_type, external_task_id)
                )
                """);
        AgentTaskStore store = new AgentTaskStore(jdbcTemplate, new ObjectMapper());
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AgentTaskRecord task = new AgentTaskRecord(UUID.randomUUID(), tenantId, userId, "ENTERPRISE",
                "runtime-1", "WAITING_APPROVAL", "Inventory review", "Approval required", "approval-1",
                new ObjectMapper().createObjectNode().put("reply", "paused"),
                java.time.Instant.now(), java.time.Instant.now());

        store.upsert(task);
        AgentTaskRecord loaded = store.findById(tenantId, task.id());
        assertNotNull(loaded);
        assertEquals("approval-1", loaded.approvalId());
        assertEquals("paused", loaded.payload().path("reply").asText());
        assertTrue(store.findByExternalId(tenantId, "ENTERPRISE", "runtime-1") != null);
        assertTrue(store.findByApprovalId(tenantId, "approval-1") != null);
        assertEquals(1, store.list(tenantId, "ENTERPRISE", "WAITING_APPROVAL", 20, 0).size());
        assertEquals(1, store.listApprovals(tenantId, 20, 0).size());
        assertEquals(1, store.countApprovals(tenantId));
        assertTrue(store.list(UUID.randomUUID(), null, null, 20, 0).isEmpty());

        AgentTaskRecord completed = new AgentTaskRecord(task.id(), tenantId, userId, "ENTERPRISE",
                "runtime-1", "COMPLETED", "Inventory review", "Done", null, task.payload(),
                task.createdAt(), java.time.Instant.now());
        store.upsert(completed);
        List<AgentTaskRecord> rows = store.list(tenantId, "ENTERPRISE", null, 20, 0);
        assertEquals(1, rows.size());
        assertEquals("COMPLETED", rows.getFirst().status());
        assertEquals(0, store.countApprovals(tenantId));

        AgentTaskRecord stale = new AgentTaskRecord(UUID.randomUUID(), tenantId, userId, "ENTERPRISE",
                "runtime-1", "RUNNING", "stale", null, null, task.payload(),
                task.createdAt(), java.time.Instant.now().minusSeconds(10));
        store.upsert(stale);
        assertEquals("COMPLETED", store.findById(tenantId, task.id()).status());
    }
}
