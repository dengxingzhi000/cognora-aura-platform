package com.cognora.platform.agent.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.BinaryRuntimeResponse;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.task.AgentTaskCenter;
import com.cognora.platform.agent.task.AgentTaskRecord;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentEnhancedControllersTest {

    private static final UUID TENANT_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000003");
    private static final UUID OTHER_USER_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000004");

    private final AgentRuntimeClient client = mock(AgentRuntimeClient.class);
    private final AgentTaskCenter taskCenter = mock(AgentTaskCenter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mvc;
    private AgentTaskRecord task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<AgentTaskCenter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(taskCenter);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AgentTaskController(provider, client, objectMapper),
                        new AgentController(client, objectMapper, provider),
                        new EnterpriseToolsController(client, objectMapper),
                        new BrowserArtifactController(client, objectMapper))
                .setControllerAdvice(new com.cognora.platform.agent.exception.AgentExceptionHandler())
                .build();
        TenantContextHolder.setTenantId(TENANT_ID);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("userId", USER_ID.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
        task = new AgentTaskRecord(UUID.randomUUID(), TENANT_ID, OTHER_USER_ID, "ENTERPRISE", "runtime-1",
                "WAITING_APPROVAL", "Inventory review", "Approval required", "approval-1",
                objectMapper.createObjectNode(), Instant.now(), Instant.now());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void unifiedTaskListIsTenantScoped() throws Exception {
        when(taskCenter.list(TENANT_ID, null, null, 20, 0)).thenReturn(List.of(task));
        when(taskCenter.count(TENANT_ID, null, null)).thenReturn(1L);

        mvc.perform(get("/api/v1/agent/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].agentType").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.records[0].approvalId").value("approval-1"));
        verify(taskCenter).syncBrowserTasks(any());
    }

    @Test
    void unifiedTaskListRejectsUnknownFilters() throws Exception {
        when(taskCenter.list(TENANT_ID, "UNKNOWN", null, 20, 0))
                .thenThrow(new IllegalArgumentException("Unsupported agent type"));
        mvc.perform(get("/api/v1/agent/tasks").param("agentType", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unifiedDecisionUsesInternalTaskIdAndTrustedTenant() throws Exception {
        when(taskCenter.find(TENANT_ID, task.id())).thenReturn(task);
        when(taskCenter.decide(any(), eq(task), eq("approve")))
                .thenReturn(new AgentTaskRecord(task.id(), TENANT_ID, OTHER_USER_ID, "ENTERPRISE", "runtime-1",
                        "APPROVED", task.title(), "Approval recorded", null, task.payload(),
                        task.createdAt(), Instant.now()));

        mvc.perform(post("/api/v1/agent/tasks/" + task.id() + "/decision")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void enterpriseTaskDetailRefreshesNonTerminalRuntimeState() throws Exception {
        AgentTaskRecord running = new AgentTaskRecord(task.id(), TENANT_ID, USER_ID, "ENTERPRISE", "runtime-1",
                "RUNNING", task.title(), null, null,
                objectMapper.createObjectNode().put("runtimeTaskId", "async-runtime-1"),
                task.createdAt(), task.updatedAt());
        AgentTaskRecord done = new AgentTaskRecord(task.id(), TENANT_ID, USER_ID, "ENTERPRISE", "runtime-1",
                "COMPLETED", task.title(), "finished", null, task.payload(), task.createdAt(), Instant.now());
        when(taskCenter.find(TENANT_ID, task.id())).thenReturn(running);
        when(taskCenter.refreshEnterprise(any(), eq(running))).thenReturn(done);

        mvc.perform(get("/api/v1/agent/tasks/" + task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void lowercaseApprovalMapsToApprovedProjection() throws Exception {
        when(client.enterprise(eq("POST"), eq("/approval/approval-1/decide"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.createObjectNode().put("status", "approved")));

        mvc.perform(post("/api/v1/agent/approvals/approval-1/decision")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\"}"))
                .andExpect(status().isOk());

        verify(taskCenter).updateEnterpriseDecision(any(), eq("approval-1"), eq("APPROVED"), any());
    }

    @Test
    void failedRuntimeDecisionDoesNotUpdateApprovalProjection() throws Exception {
        when(client.enterprise(eq("POST"), eq("/approval/approval-1/decide"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(503, objectMapper.createObjectNode().put("error", "runtime down")));

        mvc.perform(post("/api/v1/agent/approvals/approval-1/decision")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\"}"))
                .andExpect(status().isServiceUnavailable());

        verify(taskCenter, org.mockito.Mockito.never()).updateEnterpriseDecision(any(), any(), any(), any());
    }

    @Test
    void unifiedApprovalRequiresExplicitResumeEndpoint() throws Exception {
        AgentTaskRecord approved = new AgentTaskRecord(task.id(), TENANT_ID, USER_ID, "ENTERPRISE", "runtime-1",
                "APPROVED", task.title(), "Approval recorded", null, task.payload(), task.createdAt(), Instant.now());
        AgentTaskRecord done = new AgentTaskRecord(task.id(), TENANT_ID, USER_ID, "ENTERPRISE", "runtime-1",
                "COMPLETED", task.title(), "finished", null, task.payload(), task.createdAt(), Instant.now());
        when(taskCenter.find(TENANT_ID, task.id())).thenReturn(approved);
        when(taskCenter.resume(any(), eq(approved))).thenReturn(done);

        mvc.perform(post("/api/v1/agent/tasks/" + task.id() + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void workflowRunRecordsBeforeCallingRuntime() throws Exception {
        when(taskCenter.start(any(), eq("WORKFLOW"), eq("it_ops"), any())).thenReturn(task);
        when(client.enterprise(eq("POST"), eq("/workflows/it_ops/runs"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.createObjectNode()
                        .put("run_id", "run-1").put("workflow", "it_ops").put("status", "completed")
                        .set("output", objectMapper.createObjectNode().put("report", "ok"))));

        mvc.perform(post("/api/v1/agent/workflows/it_ops/runs")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\":{\"question\":\"errors\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflow").value("it_ops"));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).enterprise(eq("POST"), eq("/workflows/it_ops/runs"), isNull(), payload.capture(), any());
        assertEquals(TENANT_ID.toString(), payload.getValue().get("tenant_id"));
        assertEquals(USER_ID.toString(), payload.getValue().get("user_id"));
        verify(taskCenter).complete(eq(task), any());
    }

    @Test
    void knowledgeDocumentsAreQueriedAndIngestedThroughTrustedIdentity() throws Exception {
        when(client.enterprise(eq("GET"), eq("/knowledge/documents"), eq("limit=20&offset=0"), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.createObjectNode().putArray("records")));
        when(client.enterprise(eq("POST"), eq("/knowledge/documents"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(201, objectMapper.createObjectNode().put("documentId", "doc-1")));

        mvc.perform(get("/api/v1/agent/knowledge/documents"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/agent/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"title\":\"Policy\",\"text\":\"tenant policy\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).enterprise(eq("POST"), eq("/knowledge/documents"), isNull(), payload.capture(), any());
        assertEquals("doc-1", payload.getValue().get("document_id"));
        assertEquals("general", payload.getValue().get("department"));
        assertEquals("employee", payload.getValue().get("permission"));
        assertEquals("v1", payload.getValue().get("version"));
    }

    @Test
    void knowledgeSearchSignsQuerySeparatelyFromPath() throws Exception {
        when(client.enterprise(eq("GET"), eq("/knowledge/search"), eq("query=stock+risk&top_k=5"), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.createObjectNode().putArray("hits")));

        mvc.perform(get("/api/v1/agent/knowledge/search").param("query", "stock risk"))
                .andExpect(status().isOk());
    }

    @Test
    void artifactDownloadPreservesSafeHeadersAndBytes() throws Exception {
        when(client.browserBinary(eq("/api/v1/browser/tasks/task-1/artifacts/abcdef1234567890/content"), any()))
                .thenReturn(new BinaryRuntimeResponse(200, new byte[]{1, 2, 3}, Map.of(
                        "content-type", List.of("image/png"),
                        "content-disposition", List.of("attachment; filename=shot.png"))));

        mvc.perform(get("/api/v1/agent/browser/tasks/task-1/artifacts/abcdef1234567890/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }
}
