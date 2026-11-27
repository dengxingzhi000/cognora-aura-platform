package com.cognora.platform.agent.controller;

import java.util.Map;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class EnterpriseToolsController {

    private final AgentRuntimeClient client;
    private final ObjectMapper objectMapper;

    public EnterpriseToolsController(AgentRuntimeClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/workflows")
    @PreAuthorize("hasAuthority('agent:workflows:view')")
    public ResponseEntity<ApiResponse<Object>> workflows() {
        RuntimeResponse response = client.enterprise("GET", "/workflows", null, null, AgentController.identity());
        if (!response.isSuccess()) {
            int status = response.status() == 401 || response.status() == 403
                    || response.status() >= 500 ? 502 : response.status();
            return ResponseEntity.status(status)
                    .body(ApiResponse.fail(status, "Enterprise workflow catalog is unavailable"));
        }
        return ResponseEntity.ok(ApiResponse.success(objectMapper.convertValue(response.body(), Object.class)));
    }

    public record WorkflowRunRequest(@NotNull Map<String, Object> input) {
    }

    public record KnowledgeDocumentRequest(
            @NotBlank @Size(max = 128) String documentId,
            @NotBlank @Size(max = 200_000) String text,
            @Size(max = 80) String department,
            @Size(max = 32) String permission,
            @Size(max = 32) String version
    ) {
    }
}
