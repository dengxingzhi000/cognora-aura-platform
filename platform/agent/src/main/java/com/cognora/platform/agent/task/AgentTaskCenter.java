package com.cognora.platform.agent.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.dto.AgentIdentity;
import com.cognora.platform.agent.exception.RuntimeCallException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "cognora.agent.task-center", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentTaskCenter {

    private static final Set<String> TASK_TYPES = Set.of("ENTERPRISE", "WORKFLOW", "BROWSER");
    private static final Set<String> TASK_STATUSES = Set.of(
            "RUNNING", "PENDING", "WAITING_APPROVAL", "APPROVED", "COMPLETED", "FAILED", "CANCELLED");

    private final AgentTaskStore store;
    private final AgentRuntimeClient client;
    private final ObjectMapper objectMapper;

    public AgentTaskCenter(AgentTaskStore store, AgentRuntimeClient client, ObjectMapper objectMapper) {
        this.store = store;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public AgentTaskRecord start(AgentIdentity identity, String agentType, String title, JsonNode payload) {
        validateType(agentType);
        Instant now = Instant.now();
        String externalTaskId = UUID.randomUUID().toString();
        AgentTaskRecord task = new AgentTaskRecord(
                UUID.randomUUID(), UUID.fromString(identity.tenantId()), UUID.fromString(identity.userId()),
                agentType, externalTaskId, "RUNNING", truncate(title, 240), null, null,
                payload == null ? objectNode() : payload, now, now);
        store.upsert(task);
        return task;
    }

    public AgentTaskRecord complete(AgentTaskRecord task, RuntimeResponse response) {
        JsonNode body = response.body();
        JsonNode result = body.path("result");
        JsonNode outcome = result.isObject() ? result : body;
        String status = normalizeStatus(outcome.path("status").asText(response.isSuccess() ? "COMPLETED" : "FAILED"));
        String approvalId = outcome.path("approval_id").asText(null);
        String summary = outcome.path("reply").asText(outcome.path("summary").asText(null));
        AgentTaskRecord updated = withUpdate(task, status, summary,
                "WAITING_APPROVAL".equals(status) ? approvalId : null, boundedPayload(body));
        store.upsert(updated);
        return updated;
    }

    public AgentTaskRecord fail(AgentTaskRecord task, String summary) {
        AgentTaskRecord updated = withUpdate(task, "FAILED", truncate(summary, 1000), null,
                objectMapper.createObjectNode().put("error", truncate(summary, 1000)));
        store.upsert(updated);
        return updated;
    }

    public AgentTaskRecord runtimeUnknown(AgentTaskRecord task) {
        AgentTaskRecord updated = withUpdate(task, "RUNNING", "Runtime status unknown; refresh before retrying", null,
                objectMapper.createObjectNode().put("syncStatus", "UNKNOWN"));
        store.upsert(updated);
        return updated;
    }

    public void attachRuntimeTask(AgentIdentity identity, String externalTaskId, String runtimeTaskId) {
        if (runtimeTaskId == null || runtimeTaskId.isBlank()) {
            return;
        }
        AgentTaskRecord task = findByExternalId(identity, "ENTERPRISE", externalTaskId);
        if (task == null) {
            return;
        }
        ObjectNode payload = task.payload().isObject()
                ? (ObjectNode) task.payload().deepCopy()
                : objectNode();
        payload.put("runtimeTaskId", runtimeTaskId);
        store.upsert(withUpdate(task, task.status(), task.summary(), task.approvalId(), payload));
    }

    public AgentTaskRecord refreshEnterprise(AgentIdentity identity, AgentTaskRecord task) {
        String runtimeTaskId = task.payload().path("runtimeTaskId").asText("");
        if (runtimeTaskId.isBlank()) {
            return task;
        }
        try {
            RuntimeResponse response = client.enterprise("GET", "/tasks/" + runtimeTaskId, null, null, identity);
            if (!response.isSuccess()) {
                return task;
            }
            return complete(task, response);
        } catch (RuntimeCallException ignored) {
            return task;
        }
    }

    public void syncEnterpriseTasks(AgentIdentity identity) {
        for (AgentTaskRecord task : store.listActiveEnterprise(UUID.fromString(identity.tenantId()), 20)) {
            refreshEnterprise(identity, task);
        }
    }

    public AgentTaskRecord findByExternalId(AgentIdentity identity, String agentType, String externalTaskId) {
        return store.findByExternalId(UUID.fromString(identity.tenantId()), agentType, externalTaskId);
    }

    public AgentTaskRecord updateEnterprise(AgentIdentity identity, String externalTaskId, String status,
                                            String summary, String approvalId, JsonNode payload) {
        AgentTaskRecord task = store.findByExternalId(UUID.fromString(identity.tenantId()), "ENTERPRISE", externalTaskId);
        if (task == null) {
            return null;
        }
        AgentTaskRecord updated = updateAndStore(task, normalizeStatus(status), summary, approvalId, payload);
        return updated;
    }

    public AgentTaskRecord updateEnterpriseDecision(AgentIdentity identity, String approvalId, String status,
                                                    JsonNode payload) {
        AgentTaskRecord task = store.findByApprovalId(UUID.fromString(identity.tenantId()), approvalId);
        if (task == null) {
            return null;
        }
        return updateAndStore(task, normalizeStatus(status), null, null, payload);
    }

    public void recordBrowserTask(AgentIdentity identity, JsonNode summary) {
        String externalTaskId = summary.path("taskId").asText(null);
        if (externalTaskId == null || externalTaskId.isBlank()) {
            return;
        }
        AgentTaskRecord existing = store.findByExternalId(UUID.fromString(identity.tenantId()), "BROWSER", externalTaskId);
        String status = normalizeStatus(summary.path("status").asText("PENDING"));
        JsonNode target = summary.path("target");
        String baseTitle = existing == null ? "Browser task" : existing.title().split(" \\· ", 2)[0];
        String title = summary.path("taskType").asText(baseTitle)
                + (target.path("system").asText("").isBlank() ? "" : " · " + target.path("system").asText());
        JsonNode error = summary.path("error");
        String summaryText = error.isMissingNode() || error.isNull() ? null : truncate(error.toString(), 1000);
        String approvalId = summary.path("pendingApproval").isObject() ? externalTaskId : null;
        AgentTaskRecord task = existing == null
                ? new AgentTaskRecord(UUID.randomUUID(), UUID.fromString(identity.tenantId()), UUID.fromString(identity.userId()),
                        "BROWSER", externalTaskId, status, truncate(title, 240), summaryText, approvalId,
                        summary, Instant.now(), Instant.now())
                : withUpdate(existing, status, summaryText, approvalId, summary);
        store.upsert(task);
    }

    public void syncBrowserTasks(AgentIdentity identity) {
        try {
            RuntimeResponse response = client.browser("GET", "/api/v1/browser/tasks", "limit=100&offset=0", null, identity);
            if (!response.isSuccess()) {
                return;
            }
            for (JsonNode task : response.body().path("records")) {
                recordBrowserTask(identity, task);
            }
        } catch (RuntimeCallException ignored) {
            // The task center remains readable from its durable snapshot during runtime restarts.
        }
    }

    public AgentTaskRecord find(UUID tenantId, UUID id) {
        return store.findById(tenantId, id);
    }

    public List<AgentTaskRecord> list(UUID tenantId, String agentType, String status, int limit, int offset) {
        validateType(agentType);
        validateStatus(status);
        return store.list(tenantId, agentType, status, limit, offset);
    }

    public long count(UUID tenantId, String agentType, String status) {
        return store.count(tenantId, agentType, status);
    }

    public List<AgentTaskRecord> approvals(UUID tenantId, int limit, int offset) {
        return store.listApprovals(tenantId, limit, offset);
    }

    public long approvalCount(UUID tenantId) {
        return store.countApprovals(tenantId);
    }

    public AgentTaskRecord decide(AgentIdentity identity, AgentTaskRecord task, String decision) {
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw new IllegalArgumentException("decision must be approve or reject");
        }
        if (!"WAITING_APPROVAL".equals(task.status()) || task.approvalId() == null) {
            throw new IllegalStateException("task is not waiting for approval");
        }
        if (task.userId().toString().equals(identity.userId())) {
            throw new IllegalStateException("self approval is not allowed");
        }
        if ("BROWSER".equals(task.agentType())) {
            String action = "approve".equals(decision) ? "approve" : "reject";
            RuntimeResponse response = client.browser("POST",
                    "/api/v1/browser/tasks/" + task.externalTaskId() + "/" + action,
                    null, Map.of("actor", identity.userId()), identity);
            if (!response.isSuccess()) {
                throw new IllegalStateException("browser runtime rejected the decision");
            }
            if (response.status() == 202) {
                return updateAndStore(task, task.status(), "Decision queued for the task owner",
                        task.approvalId(), response.body());
            }
            String status = "approve".equals(decision) ? "RUNNING" : "CANCELLED";
            return updateAndStore(task, status, null, null, response.body());
        }

        RuntimeResponse decided = client.enterprise("POST", "/approval/" + task.approvalId() + "/decide",
                null, Map.of("decision", decision, "approver", identity.userId(),
                        "tenant_id", identity.tenantId()), identity);
        if (!decided.isSuccess()) {
            throw new IllegalStateException("enterprise runtime rejected the decision");
        }
        if ("reject".equals(decision)) {
            return updateAndStore(task, "CANCELLED", "Approval rejected", null, decided.body());
        }
        return updateAndStore(task, "APPROVED", "Approval recorded; resume required", null, decided.body());
    }

    public AgentTaskRecord resume(AgentIdentity identity, AgentTaskRecord task) {
        if (!"APPROVED".equals(task.status()) || !"ENTERPRISE".equals(task.agentType())) {
            throw new IllegalStateException("task is not approved for resume");
        }
        if (!task.userId().toString().equals(identity.userId())) {
            throw new IllegalStateException("only the task owner can resume");
        }
        RuntimeResponse resumed = client.enterprise("POST", "/chat/" + task.externalTaskId() + "/resume",
                null, Map.of("tenant_id", identity.tenantId()), identity);
        return complete(task, resumed);
    }

    public AgentTaskRecord cancel(AgentIdentity identity, AgentTaskRecord task) {
        if (!"BROWSER".equals(task.agentType())) {
            throw new IllegalStateException("only browser tasks support cancellation");
        }
        RuntimeResponse response = client.browser("POST", "/api/v1/browser/tasks/" + task.externalTaskId() + "/cancel",
                null, Map.of("actor", identity.userId()), identity);
        if (!response.isSuccess()) {
            throw new IllegalStateException("browser runtime rejected cancellation");
        }
        if (response.status() == 202) {
            return updateAndStore(task, task.status(), "Cancellation queued for the task owner",
                    task.approvalId(), response.body());
        }
        return updateAndStore(task, "CANCELLED", "Task cancelled", null, response.body());
    }

    private AgentTaskRecord updateAndStore(AgentTaskRecord task, String status, String summary,
                                           String approvalId, JsonNode payload) {
        AgentTaskRecord updated = withUpdate(task, status, summary, approvalId, payload);
        store.upsert(updated);
        return updated;
    }

    private AgentTaskRecord withUpdate(AgentTaskRecord task, String status, String summary,
                                       String approvalId, JsonNode payload) {
        JsonNode storedPayload = boundedPayload(payload);
        String runtimeTaskId = task.payload().path("runtimeTaskId").asText("");
        if (!runtimeTaskId.isBlank() && storedPayload.isObject()) {
            ((ObjectNode) storedPayload).put("runtimeTaskId", runtimeTaskId);
        }
        return new AgentTaskRecord(task.id(), task.tenantId(), task.userId(), task.agentType(),
                task.externalTaskId(), status, task.title(), summary, approvalId,
                storedPayload, task.createdAt(), Instant.now());
    }

    private String normalizeStatus(String value) {
        String status = value == null ? "" : value.trim().toUpperCase();
        return switch (status) {
            case "DONE", "SUCCEEDED", "OK" -> "COMPLETED";
            case "PAUSED", "AWAITING_APPROVAL" -> "WAITING_APPROVAL";
            case "QUEUED" -> "PENDING";
            default -> TASK_STATUSES.contains(status) ? status : "FAILED";
        };
    }

    private void validateType(String agentType) {
        if (agentType != null && !agentType.isBlank() && !TASK_TYPES.contains(agentType)) {
            throw new IllegalArgumentException("Unsupported agent type");
        }
    }

    private void validateStatus(String status) {
        if (status != null && !status.isBlank() && !TASK_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported task status");
        }
    }

    private ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    private JsonNode boundedPayload(JsonNode payload) {
        if (payload == null) {
            return objectNode();
        }
        String json = payload.toString();
        if (json.length() <= 100_000) {
            return payload;
        }
        return objectMapper.createObjectNode().put("truncated", true).put("preview", json.substring(0, 100_000));
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
