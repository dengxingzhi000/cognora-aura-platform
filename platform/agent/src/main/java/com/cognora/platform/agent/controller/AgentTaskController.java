package com.cognora.platform.agent.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.dto.AgentIdentity;
import com.cognora.platform.agent.task.AgentTaskCenter;
import com.cognora.platform.agent.task.AgentTaskRecord;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentTaskController {

    private final ObjectProvider<AgentTaskCenter> taskCenterProvider;
    private final AgentRuntimeClient client;
    private final ObjectMapper objectMapper;

    public AgentTaskController(ObjectProvider<AgentTaskCenter> taskCenterProvider,
                               AgentRuntimeClient client, ObjectMapper objectMapper) {
        this.taskCenterProvider = taskCenterProvider;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('agent:tasks:list')")
    public ApiResponse<Map<String, Object>> tasks(
            @RequestParam(required = false) String agentType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        validatePagination(limit, offset);
        AgentTaskCenter center = taskCenter();
        AgentIdentity identity = AgentController.identity();
        if (agentType == null || "BROWSER".equals(agentType)) {
            center.syncBrowserTasks(identity);
        }
        if (agentType == null || "ENTERPRISE".equals(agentType)) {
            center.syncEnterpriseTasks(identity);
        }
        List<AgentTaskRecord> records;
        long total;
        try {
            records = center.list(UUID.fromString(identity.tenantId()), agentType, status, limit, offset);
            total = center.count(UUID.fromString(identity.tenantId()), agentType, status);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ApiResponse.success(Map.of(
                "records", records.stream().map(task -> view(task, identity)).toList(),
                "total", total,
                "limit", limit,
                "offset", offset));
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAuthority('agent:approvals:list')")
    public ApiResponse<Map<String, Object>> approvals(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        validatePagination(limit, offset);
        AgentTaskCenter center = taskCenter();
        AgentIdentity identity = AgentController.identity();
        center.syncBrowserTasks(identity);
        center.syncEnterpriseTasks(identity);
        List<AgentTaskRecord> records = center.approvals(UUID.fromString(identity.tenantId()), limit, offset);
        long total = center.approvalCount(UUID.fromString(identity.tenantId()));
        return ApiResponse.success(Map.of(
                "records", records.stream().map(task -> view(task, identity)).toList(),
                "total", total,
                "limit", limit,
                "offset", offset));
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('agent:tasks:get')")
    public ApiResponse<Map<String, Object>> task(
            @PathVariable @Size(max = 128) String taskId) {
        AgentTaskCenter center = taskCenter();
        AgentIdentity identity = AgentController.identity();
        AgentTaskRecord task = center.find(UUID.fromString(identity.tenantId()), parseUuid(taskId));
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }
        Map<String, Object> view = view(task, identity);
        if ("BROWSER".equals(task.agentType())) {
            var response = client.browser("GET", "/api/v1/browser/tasks/" + task.externalTaskId(), null, null, identity);
            if (response.isSuccess()) {
                view.put("runtime", objectMapper.convertValue(response.body(), Object.class));
            }
        } else if ("ENTERPRISE".equals(task.agentType())
                && List.of("RUNNING", "WAITING_APPROVAL", "APPROVED").contains(task.status())) {
            AgentTaskRecord refreshed = center.refreshEnterprise(identity, task);
            if (refreshed != task) {
                view = view(refreshed, identity);
            }
        }
        return ApiResponse.success(view);
    }

    @PostMapping("/tasks/{taskId}/decision")
    @PreAuthorize("hasAuthority('agent:tasks:decide')")
    @AuditLog(operation = "决策 Agent 统一任务审批", businessType = "AGENT_TASK_APPROVAL", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ApiResponse<Map<String, Object>> decide(
            @PathVariable @Size(max = 128) String taskId,
            @Valid @RequestBody DecisionRequest request) {
        AgentTaskCenter center = taskCenter();
        AgentIdentity identity = AgentController.identity();
        AgentTaskRecord task = requireTask(center, identity, taskId);
        try {
            return ApiResponse.success(view(center.decide(identity, task, request.decision()), identity));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/resume")
    @PreAuthorize("hasAuthority('agent:tasks:resume')")
    @AuditLog(operation = "恢复 Agent 统一任务", businessType = "AGENT_TASK_APPROVAL", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ApiResponse<Map<String, Object>> resume(
            @PathVariable @Size(max = 128) String taskId) {
        AgentTaskCenter center = taskCenter();
        AgentIdentity identity = AgentController.identity();
        AgentTaskRecord task = requireTask(center, identity, taskId);
        try {
            return ApiResponse.success(view(center.resume(identity, task), identity));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @PreAuthorize("hasAuthority('agent:tasks:cancel')")
    @AuditLog(operation = "取消 Agent 统一任务", businessType = "AGENT_TASK", riskLevel = 3,
            recordParams = false, recordResult = false)
    public ApiResponse<Map<String, Object>> cancel(
            @PathVariable @Size(max = 128) String taskId) {
        AgentTaskCenter center = taskCenter();
        AgentIdentity identity = AgentController.identity();
        AgentTaskRecord task = requireTask(center, identity, taskId);
        try {
            return ApiResponse.success(view(center.cancel(identity, task), identity));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private AgentTaskRecord requireTask(AgentTaskCenter center, AgentIdentity identity, String taskId) {
        AgentTaskRecord task = center.find(UUID.fromString(identity.tenantId()), parseUuid(taskId));
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }
        return task;
    }

    private Map<String, Object> view(AgentTaskRecord task, AgentIdentity identity) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", task.id().toString());
        view.put("tenantId", task.tenantId().toString());
        view.put("userId", task.userId().toString());
        view.put("agentType", task.agentType());
        view.put("externalTaskId", task.externalTaskId());
        view.put("status", task.status());
        view.put("title", task.title());
        view.put("summary", task.summary());
        view.put("approvalId", task.approvalId());
        view.put("payload", objectMapper.convertValue(task.payload(), Object.class));
        view.put("createdAt", task.createdAt().toString());
        view.put("updatedAt", task.updatedAt().toString());
        view.put("canDecide", "WAITING_APPROVAL".equals(task.status())
                && !task.userId().toString().equals(identity.userId()));
        view.put("canResume", "APPROVED".equals(task.status())
                && "ENTERPRISE".equals(task.agentType())
                && task.userId().toString().equals(identity.userId()));
        return view;
    }

    private AgentTaskCenter taskCenter() {
        AgentTaskCenter center = taskCenterProvider.getIfAvailable();
        if (center == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent task center is disabled");
        }
        return center;
    }

    private void validatePagination(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task id");
        }
    }

    public record DecisionRequest(
            @NotNull @Pattern(regexp = "approve|reject") String decision
    ) {
    }
}
