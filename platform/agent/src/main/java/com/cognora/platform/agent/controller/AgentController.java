package com.cognora.platform.agent.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.common.constant.HttpHeaderConstants;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.dto.AgentIdentity;
import com.cognora.platform.agent.dto.AssistanceFeedbackRequest;
import com.cognora.platform.agent.dto.AssistancePredictRequest;
import com.cognora.platform.agent.dto.ApprovalDecisionRequest;
import com.cognora.platform.agent.dto.BrowserTaskRequest;
import com.cognora.platform.agent.dto.ChatRequest;
import com.cognora.platform.agent.exception.RuntimeCallException;
import com.cognora.platform.agent.exception.RuntimeProxyException;
import com.cognora.platform.agent.task.AgentTaskCenter;
import com.cognora.platform.agent.task.AgentTaskRecord;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.common.security.util.AuthTokenContext;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.common.web.util.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private static final Set<String> BROWSER_STATUSES = Set.of(
            "PENDING", "RUNNING", "WAITING_APPROVAL", "COMPLETED", "FAILED", "CANCELLED");
    private static final String SAFE_ID = "[A-Za-z0-9._-]+";

    private final AgentRuntimeClient client;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AgentTaskCenter> taskCenterProvider;

    public AgentController(AgentRuntimeClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(AgentRuntimeClient client, ObjectMapper objectMapper,
                           ObjectProvider<AgentTaskCenter> taskCenterProvider) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.taskCenterProvider = taskCenterProvider;
    }

    @GetMapping("/capabilities")
    @PreAuthorize("hasAuthority('agent:capabilities:view')")
    public ApiResponse<Object> capabilities() {
        AgentIdentity identity = identity();
        ObjectNode result = objectMapper.createObjectNode();
        result.set("enterprise", enterpriseCapabilities(identity));
        result.set("browser", browserCapabilities(identity));
        return ApiResponse.success(jsonValue(result));
    }

    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('agent:chat:run')")
    @AuditLog(operation = "执行企业 Agent 任务", businessType = "AGENT", riskLevel = 2,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> chat(@Valid @RequestBody ChatRequest request) {
        AgentIdentity identity = identity();
        AgentTaskCenter taskCenter = taskCenter();
        AgentTaskRecord task = taskCenter == null ? null : taskCenter.start(identity, "ENTERPRISE",
                request.message(), objectMapper.createObjectNode().put("message", request.message()));
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", request.message());
        if (request.sessionId() != null) {
            payload.put("session_id", request.sessionId());
        }
        payload.put("tenant_id", identity.tenantId());
        payload.put("user_id", identity.userId());
        payload.put("role", identity.role());
        if (request.assistancePredictionId() != null) {
            payload.put("assistance_prediction_id", request.assistancePredictionId());
        }
        if (task != null) {
            payload.put("task_id", task.externalTaskId());
        }
        RuntimeResponse response;
        try {
            response = client.enterprise("POST", "/chat", null, payload, identity);
        } catch (RuntimeCallException e) {
            if (task != null) {
                if (e.isStatusUnknown()) {
                    taskCenter.runtimeUnknown(task);
                } else {
                    taskCenter.fail(task, e.getMessage());
                }
            }
            throw e;
        } catch (RuntimeException e) {
            if (task != null) {
                taskCenter.fail(task, e.getMessage());
            }
            throw e;
        }
        if (task != null) {
            taskCenter.complete(task, response);
        }
        return forward(response);
    }

    @PostMapping("/assistance/predict")
    @PreAuthorize("hasAuthority('agent:assistance:predict')")
    @AuditLog(operation = "生成 AI 介入预测", businessType = "AGENT_ASSISTANCE", riskLevel = 2,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> predictAssistance(
            @Valid @RequestBody AssistancePredictRequest request) {
        AgentIdentity identity = identity();
        Map<String, Object> payload = Map.of(
                "session_id", request.sessionId(),
                "signals", objectMapper.convertValue(request.signals(), Map.class),
                "recent_events", objectMapper.convertValue(request.recentEvents(), List.class));
        return forward(client.enterprise("POST", "/assistance/predict", null, payload, identity));
    }

    @PostMapping("/assistance/predictions/{predictionId}/feedback")
    @PreAuthorize("hasAuthority('agent:assistance:feedback')")
    @AuditLog(operation = "反馈 AI 介入预测结果", businessType = "AGENT_ASSISTANCE", riskLevel = 2,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> feedbackAssistance(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String predictionId,
            @Valid @RequestBody AssistanceFeedbackRequest request) {
        AgentIdentity identity = identity();
        Map<String, Object> payload = new HashMap<>();
        payload.put("needed_assistance", request.neededAssistance());
        payload.put("assistance_used", request.assistanceUsed());
        payload.put("task_succeeded", request.taskSucceeded());
        payload.put("source", request.source());
        if (request.knowledgeHelpful() != null) {
            payload.put("knowledge_helpful", request.knowledgeHelpful());
        }
        if (request.knowledgeArticleId() != null) {
            payload.put("knowledge_article_id", request.knowledgeArticleId());
        }
        return forward(client.enterprise("POST", "/assistance/predictions/" + predictionId + "/feedback",
                null, payload, identity));
    }

    @GetMapping("/assistance/model/status")
    @PreAuthorize("hasAuthority('agent:assistance:model:view')")
    public ResponseEntity<ApiResponse<Object>> assistanceModelStatus() {
        return forward(client.enterprise("GET", "/assistance/model/status", null, null, identity()));
    }

    @GetMapping("/assistance/dashboard/data")
    @PreAuthorize("hasAuthority('agent:assistance:dashboard:view')")
    public ResponseEntity<ApiResponse<Object>> assistanceDashboardData() {
        return forward(client.enterprise("GET", "/assistance/dashboard/data", null, null, identity()));
    }

    @PostMapping("/chat/async")
    @PreAuthorize("hasAuthority('agent:chat:async')")
    @AuditLog(operation = "异步执行企业 Agent 任务", businessType = "AGENT", riskLevel = 2,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> chatAsync(@Valid @RequestBody ChatRequest request) {
        AgentIdentity identity = identity();
        AgentTaskCenter center = taskCenter();
        AgentTaskRecord task = center == null ? null : center.start(identity, "ENTERPRISE",
                request.message(), objectMapper.createObjectNode().put("message", request.message()));
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", request.message());
        if (request.sessionId() != null) {
            payload.put("session_id", request.sessionId());
        }
        if (task != null) {
            payload.put("task_id", task.externalTaskId());
        }
        payload.put("tenant_id", identity.tenantId());
        payload.put("user_id", identity.userId());
        payload.put("role", identity.role());
        if (request.assistancePredictionId() != null) {
            payload.put("assistance_prediction_id", request.assistancePredictionId());
        }
        RuntimeResponse response;
        try {
            response = client.enterprise("POST", "/chat/async", null, payload, identity);
        } catch (RuntimeCallException e) {
            if (task != null) {
                if (e.isStatusUnknown()) {
                    center.runtimeUnknown(task);
                } else {
                    center.fail(task, e.getMessage());
                }
            }
            throw e;
        } catch (RuntimeException e) {
            if (task != null) {
                center.fail(task, e.getMessage());
            }
            throw e;
        }
        if (response.isSuccess() && response.body().isObject() && task != null) {
            center.attachRuntimeTask(identity, task.externalTaskId(), response.body().path("task_id").asText());
            ObjectNode body = (ObjectNode) response.body().deepCopy();
            body.put("agent_task_id", task.externalTaskId());
            return ResponseEntity.status(response.status()).body(ApiResponse.success(jsonValue(body)));
        }
        return forward(response);
    }

    @GetMapping("/chat/tasks/{taskId}")
    @PreAuthorize("hasAuthority('agent:chat:task:get')")
    public ResponseEntity<ApiResponse<Object>> taskStatus(@PathVariable String taskId) {
        AgentIdentity identity = identity();
        RuntimeResponse response;
        try {
            response = client.enterprise("GET", "/tasks/" + taskId, null, null, identity);
        } catch (RuntimeCallException e) {
            AgentTaskCenter center = taskCenter();
            if (center != null) {
                AgentTaskRecord task = center.findByExternalId(identity, "ENTERPRISE", taskId);
                if (task != null && e.isStatusUnknown()) {
                    center.runtimeUnknown(task);
                }
            }
            throw e;
        }
        AgentTaskCenter center = taskCenter();
        if (center != null && response.isSuccess() && response.body().isObject()) {
            String externalTaskId = response.body().path("external_task_id").asText("");
            AgentTaskRecord task = center.findByExternalId(identity, "ENTERPRISE", externalTaskId);
            if (task != null) {
                String status = response.body().path("status").asText();
                if ("done".equals(status)) {
                    center.complete(task, response);
                } else if ("failed".equals(status)) {
                    center.fail(task, response.body().path("error").asText("async task failed"));
                }
            }
        }
        return forward(response);
    }

    @GetMapping("/chat/sessions/{sessionId}")
    @PreAuthorize("hasAuthority('agent:chat:history')")
    public ResponseEntity<ApiResponse<Object>> chatHistory(@PathVariable String sessionId) {
        return forward(client.enterprise("GET", "/chat/sessions/" + sessionId, null, null, identity()));
    }

    @PostMapping("/workflows/{name}/runs")
    @PreAuthorize("hasAuthority('agent:workflows:run')")
    @AuditLog(operation = "运行企业 Agent 工作流", businessType = "AGENT_WORKFLOW", riskLevel = 2,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> runWorkflow(
            @PathVariable @NotBlank @Size(max = 80) @Pattern(regexp = "[a-z0-9_-]+") String name,
            @Valid @RequestBody EnterpriseToolsController.WorkflowRunRequest request) {
        AgentIdentity identity = identity();
        String runId = UUID.randomUUID().toString();
        Map<String, Object> payload = new HashMap<>();
        payload.put("input", request.input());
        payload.put("run_id", runId);
        payload.put("tenant_id", identity.tenantId());
        payload.put("user_id", identity.userId());
        AgentTaskCenter center = taskCenter();
        AgentTaskRecord task = center == null ? null : center.start(identity, "WORKFLOW", name,
                objectMapper.valueToTree(Map.of("workflow", name, "runId", runId)));
        RuntimeResponse response;
        try {
            response = client.enterprise("POST", "/workflows/" + name + "/runs", null, payload, identity);
        } catch (RuntimeCallException e) {
            if (task != null) {
                if (e.isStatusUnknown()) {
                    center.runtimeUnknown(task);
                } else {
                    center.fail(task, e.getMessage());
                }
            }
            throw e;
        } catch (RuntimeException e) {
            if (task != null) {
                center.fail(task, e.getMessage());
            }
            throw e;
        }
        if (task != null && response.isSuccess()) {
            center.complete(task, response);
        } else if (task != null) {
            center.fail(task, response.body().path("message").asText("workflow failed"));
        }
        return forward(response);
    }

    @PostMapping("/workflow/expense/execute")
    @PreAuthorize("hasAuthority('agent:workflows:expense:execute')")
    @AuditLog(operation = "执行报销审批工作流", businessType = "AGENT", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> executeExpense(@RequestBody Map<String, Object> payload) {
        return forward(client.enterprise("POST", "/workflow/expense/execute", null, payload, identity()));
    }

    @PostMapping("/workflow/expense/{threadId}/resume")
    @PreAuthorize("hasAuthority('agent:workflows:expense:resume')")
    @AuditLog(operation = "恢复报销审批工作流", businessType = "AGENT_APPROVAL", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> resumeExpense(@PathVariable String threadId,
                                                            @RequestBody(required = false) Map<String, Object> payload) {
        return forward(client.enterprise("POST", "/workflow/expense/" + threadId + "/resume",
                null, payload == null ? Map.of() : payload, identity()));
    }

    @GetMapping("/knowledge/documents")
    @PreAuthorize("hasAuthority('agent:knowledge:view')")
    public ResponseEntity<ApiResponse<Object>> knowledgeDocuments(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, "Invalid pagination"));
        }
        return forward(client.enterprise("GET", "/knowledge/documents",
                "limit=" + limit + "&offset=" + offset, null, identity()));
    }

    @PostMapping("/knowledge/documents")
    @PreAuthorize("hasAuthority('agent:knowledge:manage')")
    @AuditLog(operation = "录入 Agent 知识文档", businessType = "AGENT_KNOWLEDGE", riskLevel = 2,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> ingestKnowledge(
            @Valid @RequestBody EnterpriseToolsController.KnowledgeDocumentRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("document_id", request.documentId());
        payload.put("text", request.text());
        payload.put("department", request.department() == null || request.department().isBlank()
                ? "general" : request.department());
        payload.put("permission", request.permission() == null || request.permission().isBlank()
                ? "employee" : request.permission());
        payload.put("version", request.version() == null || request.version().isBlank()
                ? "v1" : request.version());
        return forward(client.enterprise("POST", "/knowledge/documents", null, payload, identity()));
    }

    @GetMapping("/knowledge/search")
    @PreAuthorize("hasAuthority('agent:knowledge:search')")
    public ResponseEntity<ApiResponse<Object>> searchKnowledge(
            @RequestParam @NotBlank @Size(max = 500) String query,
            @RequestParam(defaultValue = "5") int topK) {
        if (topK < 1 || topK > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid knowledge search");
        }
        String encoded = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        return forward(client.enterprise("GET", "/knowledge/search",
                "query=" + encoded + "&top_k=" + topK, null, identity()));
    }

    @DeleteMapping("/knowledge/documents/{documentId}/{version}")
    @PreAuthorize("hasAuthority('agent:knowledge:delete')")
    @AuditLog(operation = "删除 Agent 知识文档", businessType = "AGENT_KNOWLEDGE", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> deleteKnowledge(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String documentId,
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String version) {
        return forward(client.enterprise("DELETE", "/knowledge/documents/" + documentId + "/" + version,
                null, null, identity()));
    }

    @PostMapping("/approvals/{approvalId}/decision")
    @PreAuthorize("hasAuthority('agent:approval:enterprise-decide')")
    @AuditLog(operation = "决策企业 Agent 审批", businessType = "AGENT_APPROVAL", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> decide(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        AgentIdentity identity = identity();
        Map<String, Object> payload = Map.of(
                "decision", request.decision(),
                "approver", identity.userId(),
                "tenant_id", identity.tenantId());
        RuntimeResponse response = client.enterprise("POST", "/approval/" + approvalId + "/decide", null, payload, identity);
        AgentTaskCenter center = taskCenter();
        if (center != null && response.isSuccess()) {
            center.updateEnterpriseDecision(identity, approvalId,
                    "approve".equals(request.decision()) ? "APPROVED" : "CANCELLED", response.body());
        }
        return forward(response);
    }

    @PostMapping("/chat/{taskId}/resume")
    @PreAuthorize("hasAuthority('agent:approval:enterprise-resume')")
    @AuditLog(operation = "恢复企业 Agent 审批任务", businessType = "AGENT_APPROVAL", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> resume(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String taskId) {
        AgentIdentity identity = identity();
        Map<String, Object> payload = Map.of("tenant_id", identity.tenantId());
        RuntimeResponse response = client.enterprise("POST", "/chat/" + taskId + "/resume", null, payload, identity);
        AgentTaskCenter center = taskCenter();
        if (center != null && response.isSuccess()) {
            AgentTaskRecord task = center.findByExternalId(identity, "ENTERPRISE", taskId);
            if (task != null) {
                center.complete(task, response);
            }
        }
        return forward(response);
    }

    @GetMapping("/browser/tasks")
    @PreAuthorize("hasAuthority('agent:browser:tasks:list')")
    public ResponseEntity<ApiResponse<Object>> browserTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (status != null && !BROWSER_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported browser task status");
        }
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
        StringBuilder query = new StringBuilder("limit=").append(limit).append("&offset=").append(offset);
        if (status != null && !status.isBlank()) {
            query.append("&status=").append(status);
        }
        AgentIdentity identity = identity();
        RuntimeResponse response = client.browser("GET", "/api/v1/browser/tasks", query.toString(), null, identity);
        AgentTaskCenter center = taskCenter();
        if (center != null && response.isSuccess()) {
            for (JsonNode task : response.body().path("records")) {
                center.recordBrowserTask(identity, task);
            }
        }
        return forward(response);
    }

    @PostMapping("/browser/tasks")
    @PreAuthorize("hasAuthority('agent:browser:tasks:create')")
    @AuditLog(operation = "创建浏览器自动化任务", businessType = "BROWSER_AGENT", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> createBrowserTask(
            @Valid @RequestBody BrowserTaskRequest request,
            @RequestHeader(value = HttpHeaderConstants.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        AgentIdentity identity = identity().withIdempotencyKey(normalizeIdempotencyKey(idempotencyKey));
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskType", request.taskType());
        payload.put("tenantId", identity.tenantId());
        payload.put("userId", identity.userId());
        payload.put("target", Map.of("system", request.target().system(), "url", request.target().url()));
        payload.put("input", request.input());
        payload.put("executionMode", request.executionMode());
        RuntimeResponse response = client.browser("POST", "/api/v1/browser/tasks", null, payload, identity);
        AgentTaskCenter center = taskCenter();
        if (center != null && response.isSuccess()) {
            ObjectNode summary = response.body().isObject()
                    ? ((ObjectNode) response.body()).deepCopy()
                    : objectMapper.createObjectNode();
            summary.put("taskType", request.taskType());
            summary.set("target", objectMapper.valueToTree(Map.of(
                    "system", request.target().system(), "url", request.target().url())));
            center.recordBrowserTask(identity, summary);
        }
        return response.isSuccess()
                ? ResponseEntity.status(response.status()).body(ApiResponse.success(jsonValue(response.body())))
                : forward(response);
    }

    @GetMapping("/browser/tasks/{taskId}")
    @PreAuthorize("hasAuthority('agent:browser:tasks:get')")
    public ResponseEntity<ApiResponse<Object>> browserTask(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String taskId) {
        return forward(client.browser("GET", "/api/v1/browser/tasks/" + taskId, null, null, identity()));
    }

    @GetMapping("/browser/tasks/{taskId}/steps")
    @PreAuthorize("hasAuthority('agent:browser:tasks:steps')")
    public ResponseEntity<ApiResponse<Object>> browserSteps(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String taskId) {
        return forward(client.browser("GET", "/api/v1/browser/tasks/" + taskId + "/steps", null, null, identity()));
    }

    @PostMapping("/browser/tasks/{taskId}/approve")
    @PreAuthorize("hasAuthority('agent:browser:approve')")
    @AuditLog(operation = "批准浏览器自动化步骤", businessType = "BROWSER_AGENT_APPROVAL", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> approveBrowserTask(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String taskId) {
        return browserDecision(taskId, "approve");
    }

    @PostMapping("/browser/tasks/{taskId}/reject")
    @PreAuthorize("hasAuthority('agent:browser:reject')")
    @AuditLog(operation = "驳回浏览器自动化步骤", businessType = "BROWSER_AGENT_APPROVAL", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> rejectBrowserTask(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String taskId) {
        return browserDecision(taskId, "reject");
    }

    @PostMapping("/browser/tasks/{taskId}/cancel")
    @PreAuthorize("hasAuthority('agent:browser:cancel')")
    @AuditLog(operation = "取消浏览器自动化任务", businessType = "BROWSER_AGENT", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> cancelBrowserTask(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String taskId) {
        AgentIdentity identity = identity();
        RuntimeResponse response = client.browser("POST", "/api/v1/browser/tasks/" + taskId + "/cancel",
                null, Map.of("actor", identity.userId()), identity);
        recordBrowserAction(identity, taskId, response);
        return forward(response);
    }

    private ResponseEntity<ApiResponse<Object>> browserDecision(String taskId, String action) {
        AgentIdentity identity = identity();
        RuntimeResponse response = client.browser("POST", "/api/v1/browser/tasks/" + taskId + "/" + action,
                null, Map.of("actor", identity.userId()), identity);
        recordBrowserAction(identity, taskId, response);
        return forward(response);
    }

    private void recordBrowserAction(AgentIdentity identity, String taskId, RuntimeResponse response) {
        AgentTaskCenter center = taskCenter();
        if (center == null || !response.isSuccess() || response.status() == 202) {
            return;
        }
        ObjectNode summary = response.body().isObject()
                ? ((ObjectNode) response.body()).deepCopy()
                : objectMapper.createObjectNode();
        summary.put("taskId", taskId);
        summary.put("status", response.body().path("status").asText("RUNNING"));
        center.recordBrowserTask(identity, summary);
    }

    private ObjectNode enterpriseCapabilities(AgentIdentity identity) {
        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.putArray("tools");
        capabilities.put("knowledge", false);
        capabilities.put("memory", false);
        capabilities.put("workflows", false);
        capabilities.put("mcpInstalled", false);
        capabilities.put("writingAssistant", false);
        try {
            RuntimeResponse response = client.enterprise("GET", "/capabilities", null, null, identity);
            if (response.isSuccess()) {
                JsonNode body = response.body();
                capabilities.set("tools", body.path("tools").isArray() ? body.path("tools") : capabilities.get("tools"));
                capabilities.put("knowledge", body.path("knowledge").asBoolean(false));
                capabilities.put("memory", body.path("memory").asBoolean(false));
                capabilities.put("workflows", body.path("workflows").asBoolean(false));
                capabilities.put("mcpInstalled", body.path("mcpInstalled").asBoolean(body.path("mcp").asBoolean(false)));
                capabilities.put("writingAssistant", body.path("writingAssistant").asBoolean(false));
            }
        } catch (RuntimeCallException ignored) {
            // The workbench remains usable for the other runtime while one is restarting.
        }
        return capabilities;
    }

    private ObjectNode browserCapabilities(AgentIdentity identity) {
        ObjectNode capabilities = objectMapper.createObjectNode();
        ArrayNode tools = capabilities.putArray("tools");
        capabilities.putArray("workflows");
        capabilities.put("approvalRequiredForSensitiveActions", true);
        try {
            RuntimeResponse toolResponse = client.browser("GET", "/api/v1/browser/tools", null, null, identity);
            if (toolResponse.isSuccess() && toolResponse.body().path("tools").isArray()) {
                tools.addAll((ArrayNode) toolResponse.body().path("tools"));
            }
            RuntimeResponse workflowResponse = client.browser("GET", "/api/v1/browser/workflows", null, null, identity);
            if (workflowResponse.isSuccess() && workflowResponse.body().path("workflows").isArray()) {
                capabilities.set("workflows", workflowResponse.body().path("workflows"));
            }
        } catch (RuntimeCallException ignored) {
            // Partial capability reporting keeps the BFF available during runtime restarts.
        }
        return capabilities;
    }

    private ResponseEntity<ApiResponse<Object>> forward(RuntimeResponse response) {
        if (response.isSuccess()) {
            return ResponseEntity.status(response.status()).body(ApiResponse.success(jsonValue(response.body())));
        }
        HttpStatus status = switch (response.status()) {
            case 400, 404, 409, 413, 422, 429, 503 -> HttpStatus.valueOf(response.status());
            default -> HttpStatus.BAD_GATEWAY;
        };
        String message = status == HttpStatus.BAD_GATEWAY
                ? "Agent runtime rejected the request"
                : "Agent runtime could not complete the request";
        throw new RuntimeProxyException(status, message);
    }

    private AgentTaskCenter taskCenter() {
        return taskCenterProvider == null ? null : taskCenterProvider.getIfAvailable();
    }

    private Object jsonValue(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    static AgentIdentity identity() {
        String tenantId = TenantContextHolder.getRequiredTenantId().toString();
        String userId = SecurityUtils.requireCurrentUserUuid().toString();
        String role = SecurityUtils.getAuthorities().stream()
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .reduce(AgentController::higherPrivilegeRole)
                .orElse("employee");
        return new AgentIdentity(tenantId, userId, canonicalRole(role), currentPlatformToken(), currentDeviceId(), null);
    }

    private static String currentDeviceId() {
        String deviceId = AuthTokenContext.getDeviceId();
        if (deviceId != null && !deviceId.isBlank()) {
            return deviceId;
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        String headerDeviceId = attributes.getRequest().getHeader(HttpHeaderConstants.DEVICE_ID);
        return headerDeviceId == null || headerDeviceId.isBlank() ? null : headerDeviceId.trim();
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String key = value.trim();
        if (key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        return key;
    }

    private static String currentPlatformToken() {
        String token = AuthTokenContext.getToken();
        if (token != null && !token.isBlank()) {
            return token;
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        String authorization = attributes.getRequest().getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    private static String canonicalRole(String role) {
        return switch (role) {
            case "SUPER_ADMIN", "TENANT_ADMIN" -> "admin";
            case "FINANCE" -> "finance";
            case "DEPARTMENT_MANAGER", "MANAGER" -> "manager";
            default -> "employee";
        };
    }

    private static String higherPrivilegeRole(String left, String right) {
        return priority(left) >= priority(right) ? left : right;
    }

    private static int priority(String role) {
        return switch (role) {
            case "SUPER_ADMIN", "TENANT_ADMIN" -> 5;
            case "FINANCE" -> 4;
            case "DEPARTMENT_MANAGER", "MANAGER" -> 3;
            case "OPERATOR" -> 2;
            default -> 1;
        };
    }
}
