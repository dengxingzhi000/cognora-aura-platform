package com.cognora.platform.agent.dto;

public record AgentIdentity(String tenantId, String userId, String role,
                            String platformToken, String deviceId, String idempotencyKey) {
    public AgentIdentity(String tenantId, String userId, String role) {
        this(tenantId, userId, role, null, null, null);
    }

    public AgentIdentity(String tenantId, String userId, String role, String platformToken, String idempotencyKey) {
        this(tenantId, userId, role, platformToken, null, idempotencyKey);
    }

    public AgentIdentity withIdempotencyKey(String idempotencyKey) {
        return new AgentIdentity(tenantId, userId, role, platformToken, deviceId, idempotencyKey);
    }
}
