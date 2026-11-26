package com.cognora.platform.agent.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AssistancePredictRequest(
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+")
        @JsonProperty("session_id") String sessionId,
        @Valid @NotNull AssistanceSignals signals,
        @Valid @Size(max = 60) @JsonProperty("recent_events") List<AssistanceEvent> recentEvents
) {
    private static final String EVENT_TYPE = "PAGE_VIEW|FORM_EDIT|VALIDATION_ERROR|API_ERROR|RETRY|"
            + "BACK_NAVIGATION|CONTEXT_SWITCH|PERMISSION_DENIED|HELP_OPEN|IDLE|TASK_SUCCESS|TASK_FAILURE|CANCEL";
    private static final String ERROR_CODE = "VALIDATION|UNAUTHORIZED|FORBIDDEN|NOT_FOUND|CONFLICT|"
            + "RATE_LIMITED|SERVER_ERROR|TIMEOUT|NETWORK|BUSINESS_REJECTION|UNKNOWN";

    public AssistancePredictRequest {
        if (recentEvents == null) {
            recentEvents = List.of();
        }
    }

    public record AssistanceEvent(
            @NotBlank @Pattern(regexp = EVENT_TYPE) String type,
            @NotNull @Min(0) @Max(604800000) @JsonProperty("offset_ms") Long offsetMs,
            @NotNull @Min(0) @Max(604800000) @JsonProperty("duration_ms") Long durationMs,
            @NotNull @Min(0) @Max(3) @JsonProperty("severity") Integer severity,
            @Pattern(regexp = ERROR_CODE) @JsonProperty("error_code") String errorCode
    ) {
    }

    public record AssistanceSignals(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String module,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,127}") String workflow,
            @NotNull @Min(0) @Max(604800) @JsonProperty("session_duration_seconds") Long sessionDurationSeconds,
            @NotNull @Min(0) @Max(604800) @JsonProperty("active_seconds") Long activeSeconds,
            @NotNull @Min(0) @Max(604800) @JsonProperty("idle_seconds") Long idleSeconds,
            @NotNull @Min(0) @Max(100000) @JsonProperty("event_count") Long eventCount,
            @NotNull @Min(0) @Max(10000) @JsonProperty("error_count") Long errorCount,
            @NotNull @Min(0) @Max(1000) @JsonProperty("unique_error_count") Long uniqueErrorCount,
            @NotNull @Min(0) @Max(10000) @JsonProperty("repeated_error_count") Long repeatedErrorCount,
            @NotNull @Min(0) @Max(10000) @JsonProperty("retry_count") Long retryCount,
            @NotNull @Min(0) @Max(10000) @JsonProperty("back_navigation_count") Long backNavigationCount,
            @NotNull @Min(0) @Max(10000) @JsonProperty("context_switch_count") Long contextSwitchCount,
            @NotNull @Min(0) @Max(10000) @JsonProperty("validation_failure_count") Long validationFailureCount,
            @NotNull @Min(0) @Max(1000) @JsonProperty("permission_denied_count") Long permissionDeniedCount,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") @JsonProperty("form_completion_ratio") BigDecimal formCompletionRatio,
            @NotNull @Min(0) @Max(1000) @JsonProperty("help_open_count") Long helpOpenCount,
            @NotNull @Min(0) @Max(1000000) @JsonProperty("previous_task_count") Long previousTaskCount,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") @JsonProperty("previous_success_rate") BigDecimal previousSuccessRate,
            @NotNull @Min(0) @Max(10000) @JsonProperty("recent_assistance_count") Long recentAssistanceCount,
            @NotNull @JsonProperty("explicit_help_request") Boolean explicitHelpRequest,
            @NotNull @JsonProperty("high_risk_action") Boolean highRiskAction
    ) {
    }
}
