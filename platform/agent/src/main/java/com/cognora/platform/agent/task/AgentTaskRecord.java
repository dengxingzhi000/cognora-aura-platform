package com.cognora.platform.agent.task;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentTaskRecord(
        UUID id,
        UUID tenantId,
        UUID userId,
        String agentType,
        String externalTaskId,
        String status,
        String title,
        String summary,
        String approvalId,
        JsonNode payload,
        Instant createdAt,
        Instant updatedAt
) {
}
