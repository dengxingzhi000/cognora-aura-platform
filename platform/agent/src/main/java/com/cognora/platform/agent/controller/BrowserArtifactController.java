package com.cognora.platform.agent.controller;

import java.util.List;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.BinaryRuntimeResponse;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/browser")
public class BrowserArtifactController {

    private static final String SAFE_ID = "[A-Za-z0-9._-]+";
    private static final String ARTIFACT_ID = "[A-Za-z0-9_-]{16,128}";

    private final AgentRuntimeClient client;
    private final ObjectMapper objectMapper;

    public BrowserArtifactController(AgentRuntimeClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/tasks/{taskId}/artifacts")
    @PreAuthorize("hasAuthority('agent:browser:artifacts:list')")
    public ResponseEntity<ApiResponse<Object>> artifacts(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String taskId) {
        RuntimeResponse response = client.browser("GET", "/api/v1/browser/tasks/" + taskId + "/artifacts",
                null, null, AgentController.identity());
        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(objectMapper.convertValue(response.body(), Object.class)));
        }
        return ResponseEntity.status(mapStatus(response.status()))
                .body(ApiResponse.fail(mapStatus(response.status()).value(), "Browser artifacts are unavailable"));
    }

    @GetMapping("/tasks/{taskId}/artifacts/{artifactId}/content")
    @PreAuthorize("hasAuthority('agent:browser:artifacts:view')")
    @AuditLog(operation = "下载浏览器 Agent 产物", businessType = "BROWSER_AGENT_ARTIFACT", riskLevel = 2,
            recordParams = false, recordResult = false)
    public ResponseEntity<byte[]> artifactContent(
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_ID) String taskId,
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = ARTIFACT_ID) String artifactId) {
        BinaryRuntimeResponse response = client.browserBinary(
                "/api/v1/browser/tasks/" + taskId + "/artifacts/" + artifactId + "/content",
                AgentController.identity());
        if (!response.isSuccess()) {
            return ResponseEntity.status(mapStatus(response.status())).build();
        }
        HttpHeaders headers = new HttpHeaders();
        header(response, "content-type").ifPresent(value -> headers.setContentType(MediaType.parseMediaType(value)));
        header(response, "content-disposition").ifPresent(value -> headers.set("Content-Disposition", value));
        header(response, "content-length").ifPresent(value -> headers.set("Content-Length", value));
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "private, no-store");
        if (headers.getContentType() == null) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        return ResponseEntity.ok().headers(headers).body(response.body());
    }

    private java.util.Optional<String> header(BinaryRuntimeResponse response, String name) {
        return java.util.Optional.ofNullable(response.headers().get(name))
                .filter(values -> !values.isEmpty())
                .map(List::getFirst)
                .filter(value -> !value.contains("\r") && !value.contains("\n"));
    }

    private HttpStatus mapStatus(int status) {
        return switch (status) {
            case 400, 404 -> HttpStatus.valueOf(status);
            case 403 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }
}
