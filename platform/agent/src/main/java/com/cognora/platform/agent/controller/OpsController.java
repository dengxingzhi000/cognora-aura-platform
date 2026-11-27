package com.cognora.platform.agent.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.dto.AgentIdentity;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/ops")
public class OpsController {

    private static final String SAFE_NAME = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}";
    private static final String SAFE_IMAGE = "[A-Za-z0-9._/:@-]{1,512}";

    private final AgentRuntimeClient client;
    private final ObjectMapper objectMapper;

    public OpsController(AgentRuntimeClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/catalog")
    @PreAuthorize("hasAuthority('ops:catalog:view')")
    public ResponseEntity<ApiResponse<Object>> catalog() {
        return forward(client.enterprise("GET", "/ops/catalog", null, null, AgentController.identity()));
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('ops:view')")
    public ResponseEntity<ApiResponse<Object>> status(
            @RequestParam(defaultValue = "agent") @Pattern(regexp = "minimal|agent") String profile) {
        return forward(client.enterprise("GET", "/ops/status", "profile=" + profile, null, AgentController.identity()));
    }

    @PostMapping("/actions")
    @PreAuthorize("hasAuthority('ops:deploy')")
    @AuditLog(operation = "执行运维控制台操作", businessType = "OPS", riskLevel = 2,
            recordParams = false, recordResult = false)
    public ResponseEntity<ApiResponse<Object>> action(@Valid @RequestBody OpsActionRequest request) {
        AgentIdentity identity = AgentController.identity();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", request.action());
        if (request.profile() != null) payload.put("profile", request.profile());
        if (request.services() != null) payload.put("services", request.services());
        if (request.service() != null) payload.put("service", request.service());
        if (request.image() != null) payload.put("image", request.image());
        payload.put("apply", request.apply());
        return forward(client.enterprise("POST", "/ops/actions", null, payload, identity));
    }

    private ResponseEntity<ApiResponse<Object>> forward(RuntimeResponse response) {
        if (response.isSuccess()) {
            return ResponseEntity.status(response.status()).body(ApiResponse.success(
                    objectMapper.convertValue(response.body(), Object.class)));
        }
        HttpStatus status = response.status() == 401 || response.status() == 403
                ? HttpStatus.BAD_GATEWAY : mapStatus(response.status());
        String message = status == HttpStatus.BAD_GATEWAY
                ? "运维控制面暂时不可用" : "运维操作未完成";
        return ResponseEntity.status(status).body(ApiResponse.fail(status.value(), message));
    }

    private HttpStatus mapStatus(int status) {
        return switch (status) {
            case 400, 404, 409, 422, 429 -> HttpStatus.valueOf(status);
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    public record OpsActionRequest(
            @NotBlank @Pattern(regexp = "START|STOP|RESTART|DEPLOY_IMAGE|ROLLBACK") String action,
            @Pattern(regexp = "minimal|agent") String profile,
            @Size(max = 32) List<@Pattern(regexp = SAFE_NAME) String> services,
            @Pattern(regexp = SAFE_NAME) String service,
            @Pattern(regexp = SAFE_IMAGE) String image,
            boolean apply
    ) {
    }
}
