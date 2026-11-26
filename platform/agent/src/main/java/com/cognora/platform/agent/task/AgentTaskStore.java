package com.cognora.platform.agent.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "cognora.agent.task-center", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentTaskStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<AgentTaskRecord> rowMapper;

    public AgentTaskStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    public void upsert(AgentTaskRecord task) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO platform_agent_task
                        (id, tenant_id, user_id, agent_type, external_task_id, status, title,
                         summary, approval_id, payload, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """,
                    task.id(), task.tenantId(), task.userId(), task.agentType(), task.externalTaskId(),
                    task.status(), task.title(), task.summary(), task.approvalId(),
                    task.payload().toString(), task.createdAt(), task.updatedAt());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            int updated = jdbcTemplate.update("""
                    UPDATE platform_agent_task SET
                        status = ?, title = ?, summary = ?, approval_id = ?, payload = CAST(? AS jsonb),
                        updated_at = ?
                    WHERE tenant_id = ? AND agent_type = ? AND external_task_id = ?
                      AND (
                          CASE status
                              WHEN 'RUNNING' THEN 1
                              WHEN 'PENDING' THEN 1
                              WHEN 'WAITING_APPROVAL' THEN 2
                              WHEN 'APPROVED' THEN 3
                              WHEN 'COMPLETED' THEN 4
                              WHEN 'FAILED' THEN 4
                              WHEN 'CANCELLED' THEN 4
                              ELSE 0
                          END
                          <
                          ?
                          OR (
                              CASE status
                                  WHEN 'RUNNING' THEN 1
                                  WHEN 'PENDING' THEN 1
                                  WHEN 'WAITING_APPROVAL' THEN 2
                                  WHEN 'APPROVED' THEN 3
                                  WHEN 'COMPLETED' THEN 4
                                  WHEN 'FAILED' THEN 4
                                  WHEN 'CANCELLED' THEN 4
                                  ELSE 0
                              END
                              =
                              ?
                              AND updated_at <= ?
                          )
                      )
                    """,
                    task.status(), task.title(), task.summary(), task.approvalId(),
                    task.payload().toString(), task.updatedAt(), task.tenantId(), task.agentType(),
                    task.externalTaskId(), statusRank(task.status()), statusRank(task.status()), task.updatedAt());
            if (updated != 1 && findByExternalId(task.tenantId(), task.agentType(), task.externalTaskId()) == null) {
                throw e;
            }
        }
    }

    public AgentTaskRecord findById(UUID tenantId, UUID id) {
        List<AgentTaskRecord> rows = jdbcTemplate.query(
                "SELECT * FROM platform_agent_task WHERE tenant_id = ? AND id = ?", rowMapper, tenantId, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public AgentTaskRecord findByExternalId(UUID tenantId, String agentType, String externalTaskId) {
        List<AgentTaskRecord> rows = jdbcTemplate.query(
                "SELECT * FROM platform_agent_task WHERE tenant_id = ? AND agent_type = ? AND external_task_id = ?",
                rowMapper, tenantId, agentType, externalTaskId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public AgentTaskRecord findByApprovalId(UUID tenantId, String approvalId) {
        List<AgentTaskRecord> rows = jdbcTemplate.query(
                "SELECT * FROM platform_agent_task WHERE tenant_id = ? AND approval_id = ? ORDER BY updated_at DESC LIMIT 1",
                rowMapper, tenantId, approvalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<AgentTaskRecord> listActiveEnterprise(UUID tenantId, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM platform_agent_task
                WHERE tenant_id = ? AND agent_type = 'ENTERPRISE'
                  AND status IN ('RUNNING', 'PENDING', 'WAITING_APPROVAL', 'APPROVED')
                ORDER BY updated_at DESC LIMIT ?
                """, rowMapper, tenantId, limit);
    }

    public List<AgentTaskRecord> list(UUID tenantId, String agentType, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM platform_agent_task WHERE tenant_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(tenantId);
        if (agentType != null && !agentType.isBlank()) {
            sql.append(" AND agent_type = ?");
            params.add(agentType);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());
    }

    public long count(UUID tenantId, String agentType, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM platform_agent_task WHERE tenant_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(tenantId);
        if (agentType != null && !agentType.isBlank()) {
            sql.append(" AND agent_type = ?");
            params.add(agentType);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    public List<AgentTaskRecord> listApprovals(UUID tenantId, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT * FROM platform_agent_task
                WHERE tenant_id = ? AND status = 'WAITING_APPROVAL' AND approval_id IS NOT NULL
                ORDER BY updated_at ASC LIMIT ? OFFSET ?
                """, rowMapper, tenantId, limit, offset);
    }

    public long countApprovals(UUID tenantId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM platform_agent_task
                WHERE tenant_id = ? AND status = 'WAITING_APPROVAL' AND approval_id IS NOT NULL
                """, Long.class, tenantId);
        return count == null ? 0 : count;
    }

    private int statusRank(String status) {
        return switch (status) {
            case "RUNNING", "PENDING" -> 1;
            case "WAITING_APPROVAL" -> 2;
            case "APPROVED" -> 3;
            case "COMPLETED", "FAILED", "CANCELLED" -> 4;
            default -> 0;
        };
    }

    private AgentTaskRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rs.getString("payload"));
            if (payload != null && payload.isTextual()) {
                payload = objectMapper.readTree(payload.textValue());
            }
            if (payload == null) {
                payload = objectMapper.createObjectNode();
            }
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid task payload", e);
        }
        return new AgentTaskRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("agent_type"),
                rs.getString("external_task_id"),
                rs.getString("status"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("approval_id"),
                payload,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
