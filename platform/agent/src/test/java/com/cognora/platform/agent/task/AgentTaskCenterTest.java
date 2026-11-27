package com.cognora.platform.agent.task;

import java.util.List;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.dto.AgentIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskCenterTest {

    private final AgentTaskStore store = mock(AgentTaskStore.class);
    private final AgentRuntimeClient client = mock(AgentRuntimeClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentTaskCenter center = new AgentTaskCenter(store, client, objectMapper);
    private final AgentIdentity identity = new AgentIdentity(
            "019a0aee-0000-7000-8000-000000000002",
            "019a0aee-0000-7000-8000-000000000003", "admin");
    private static final UUID OTHER_USER_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000004");

    private AgentTaskRecord task;

    @BeforeEach
    void setUp() {
        task = new AgentTaskRecord(UUID.randomUUID(), UUID.fromString(identity.tenantId()), OTHER_USER_ID,
                "ENTERPRISE", "runtime-1", "WAITING_APPROVAL", "Inventory review", "Approval required", "approval-1",
                objectMapper.createObjectNode(), java.time.Instant.now(), java.time.Instant.now());
    }

    @Test
    void recordsPausedApprovalAndNormalizesDoneStatus() {
        AgentTaskRecord started = center.start(identity, "ENTERPRISE", "Check inventory",
                objectMapper.createObjectNode().put("message", "check"));
        verify(store).upsert(started);
        assertEquals("RUNNING", started.status());

        when(client.enterprise(eq("POST"), eq("/chat"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.createObjectNode()
                        .put("status", "paused").put("reply", "need approval").put("approval_id", "approval-1")));
        AgentTaskRecord completed = center.complete(started,
                new RuntimeResponse(200, objectMapper.createObjectNode()
                        .put("status", "paused").put("reply", "need approval").put("approval_id", "approval-1")));
        assertEquals("WAITING_APPROVAL", completed.status());
        assertEquals("approval-1", completed.approvalId());

        AgentTaskRecord done = center.complete(started, new RuntimeResponse(200,
                objectMapper.createObjectNode().put("status", "done").put("reply", "finished")));
        assertEquals("COMPLETED", done.status());
    }

    @Test
    void asyncDoneWrapperPreservesPausedApprovalResult() {
        AgentTaskRecord started = center.start(identity, "ENTERPRISE", "Check inventory",
                objectMapper.createObjectNode().put("message", "check"));
        AgentTaskRecord attached = storeUpsertResult(started.externalTaskId());
        center.attachRuntimeTask(identity, started.externalTaskId(), "async-runtime-1");
        verify(store, org.mockito.Mockito.atLeastOnce()).upsert(org.mockito.ArgumentMatchers.argThat(record ->
                "async-runtime-1".equals(record.payload().path("runtimeTaskId").asText())));
        when(client.enterprise(eq("GET"), eq("/tasks/async-runtime-1"), isNull(), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.createObjectNode()
                        .put("status", "done")
                        .set("result", objectMapper.createObjectNode()
                                .put("status", "paused").put("reply", "need approval")
                                .put("approval_id", "approval-async"))));

        AgentTaskRecord refreshed = center.refreshEnterprise(identity, attached);

        assertEquals("WAITING_APPROVAL", refreshed.status());
        assertEquals("approval-async", refreshed.approvalId());
        assertEquals("async-runtime-1", refreshed.payload().path("runtimeTaskId").asText());
    }

    private AgentTaskRecord storeUpsertResult(String externalTaskId) {
        AgentTaskRecord attached = new AgentTaskRecord(task.id(), task.tenantId(), task.userId(), "ENTERPRISE",
                externalTaskId, "RUNNING", task.title(), null, null,
                objectMapper.createObjectNode().put("runtimeTaskId", "async-runtime-1"),
                task.createdAt(), task.updatedAt());
        when(store.findByExternalId(UUID.fromString(identity.tenantId()), "ENTERPRISE", externalTaskId))
                .thenReturn(attached);
        return attached;
    }

    @Test
    void enterpriseApprovalDoesNotResumeUntilExplicitResume() {
        when(store.findByExternalId(UUID.fromString(identity.tenantId()), "ENTERPRISE", "runtime-1")).thenReturn(task);
        when(client.enterprise(eq("POST"), eq("/approval/approval-1/decide"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.createObjectNode().put("status", "approved")));

        AgentTaskRecord approved = center.decide(identity, task, "approve");
        assertEquals("APPROVED", approved.status());
        verify(client, never()).enterprise(eq("POST"), eq("/chat/runtime-1/resume"), isNull(), any(), any());

        AgentTaskRecord owned = new AgentTaskRecord(task.id(), task.tenantId(), UUID.fromString(identity.userId()),
                task.agentType(), task.externalTaskId(), "APPROVED", task.title(), "Approval recorded", null,
                task.payload(), task.createdAt(), task.updatedAt());
        when(client.enterprise(eq("POST"), eq("/chat/runtime-1/resume"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.createObjectNode()
                        .put("status", "done").put("reply", "executed")));
        AgentTaskRecord resumed = center.resume(identity, owned);
        assertEquals("COMPLETED", resumed.status());
    }

    @Test
    void selfApprovalAndNonOwnerResumeAreRejected() {
        AgentTaskRecord ownedByCaller = new AgentTaskRecord(task.id(), task.tenantId(), UUID.fromString(identity.userId()),
                task.agentType(), task.externalTaskId(), task.status(), task.title(), task.summary(), task.approvalId(),
                task.payload(), task.createdAt(), task.updatedAt());
        assertThrows(IllegalStateException.class, () -> center.decide(identity, ownedByCaller, "approve"));

        AgentTaskRecord approved = new AgentTaskRecord(task.id(), task.tenantId(), OTHER_USER_ID,
                task.agentType(), task.externalTaskId(), "APPROVED", task.title(), null, null,
                task.payload(), task.createdAt(), task.updatedAt());
        assertThrows(IllegalStateException.class, () -> center.resume(identity, approved));
    }

    @Test
    void queuedBrowserDecisionRetainsApprovalState() {
        AgentTaskRecord browserTask = new AgentTaskRecord(task.id(), task.tenantId(), OTHER_USER_ID, "BROWSER",
                "task-browser-1", "WAITING_APPROVAL", task.title(), null, "task-browser-1",
                task.payload(), task.createdAt(), task.updatedAt());
        when(client.browser(eq("POST"), eq("/api/v1/browser/tasks/task-browser-1/approve"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(202, objectMapper.createObjectNode().put("approval", "queued")));

        AgentTaskRecord queued = center.decide(identity, browserTask, "approve");

        assertEquals("WAITING_APPROVAL", queued.status());
        assertEquals("task-browser-1", queued.approvalId());
    }

    @Test
    void runtimeTimeoutKeepsLastKnownStateAndMarksUnknown() {
        AgentTaskRecord unknown = center.runtimeUnknown(task);
        assertEquals("RUNNING", unknown.status());
        assertEquals("UNKNOWN", unknown.payload().path("syncStatus").asText());
    }

    @Test
    void browserSyncUsesRuntimeListAndTenantScopedProjection() {
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("records").add(objectMapper.createObjectNode().put("taskId", "task-1")
                .put("taskType", "product.create").put("status", "WAITING_APPROVAL")
                .set("pendingApproval", objectMapper.createObjectNode()));
        when(client.browser(eq("GET"), eq("/api/v1/browser/tasks"), eq("limit=100&offset=0"), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, payload));

        center.syncBrowserTasks(identity);
        verify(store).upsert(org.mockito.ArgumentMatchers.argThat(record ->
                "BROWSER".equals(record.agentType()) && "task-1".equals(record.externalTaskId())
                        && "WAITING_APPROVAL".equals(record.status())));
    }
}
