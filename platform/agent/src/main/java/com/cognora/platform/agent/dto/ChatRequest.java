package com.cognora.platform.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 8000) String message,
        @Size(max = 128) String sessionId,
        @Size(max = 128) String taskId,
        @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._-]+")
        @JsonProperty("assistance_prediction_id") String assistancePredictionId
) {
    public ChatRequest(String message) {
        this(message, null, null, null);
    }

    public ChatRequest(String message, String sessionId) {
        this(message, sessionId, null, null);
    }

    public ChatRequest(String message, String sessionId, String taskId) {
        this(message, sessionId, taskId, null);
    }
}
