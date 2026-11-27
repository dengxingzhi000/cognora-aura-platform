package com.cognora.platform.agent.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.dto.AgentIdentity;
import com.cognora.platform.agent.dto.WritingAssistanceRequest;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class WritingAssistantController {

    private final AgentRuntimeClient client;
    private final ObjectMapper objectMapper;

    public WritingAssistantController(AgentRuntimeClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/writing-assistant")
    @PreAuthorize("hasAuthority('agent:writing:use')")
    @AuditLog(operation = "AI 辅助生成业务描述", businessType = "AGENT_WRITING", riskLevel = 1,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> generate(@Valid @RequestBody WritingAssistanceRequest request) {
        AgentIdentity identity = AgentController.identity();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scene", request.scene());
        payload.put("action", request.action());
        payload.put("content", request.content());
        payload.put("context", request.context());
        payload.put("max_length", request.maxLength());
        payload.put("language", request.language());
        RuntimeResponse response = client.enterprise("POST", "/writing-assistant", null, payload, identity);
        if (response.isSuccess()) {
            String text = response.body().path("text").asText("");
            if (text.isBlank() || text.length() > request.maxLength()) {
                return failure(HttpStatus.BAD_GATEWAY, "AI 写作助手生成失败，请重试");
            }
            return ResponseEntity.status(response.status()).body(ApiResponse.success(
                    objectMapper.convertValue(response.body(), Object.class)));
        }
        return failure(mapStatus(response.status()), messageFor(response));
    }

    private HttpStatus mapStatus(int runtimeStatus) {
        return switch (runtimeStatus) {
            case 422, 429, 503 -> HttpStatus.valueOf(runtimeStatus);
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private String messageFor(RuntimeResponse response) {
        if (response.status() == 422) {
            String code = response.body().path("detail").path("code").asText("");
            return "SENSITIVE_CONTENT_REJECTED".equals(code)
                    ? "请勿提交密码、密钥等敏感信息"
                    : "请求内容不符合 AI 写作助手要求";
        }
        return switch (response.status()) {
            case 429 -> "AI 生成过于频繁，请稍后再试";
            case 503 -> "AI 写作助手尚未配置或暂不可用";
            default -> "AI 写作助手生成失败，请重试";
        };
    }

    private ResponseEntity<ApiResponse<Object>> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(status.value(), message));
    }
}
