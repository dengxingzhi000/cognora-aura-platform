package com.cognora.platform.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BrowserTaskRequest(
        @NotBlank @Size(max = 80) String taskType,
        @NotNull @Valid Target target,
        @NotNull Object input,
        @NotNull @Pattern(regexp = "auto|require_approval") String executionMode
) {
    public BrowserTaskRequest {
        if (!(input instanceof java.util.Map<?, ?>)) {
            throw new IllegalArgumentException("input must be a JSON object");
        }
    }

    public record Target(
            @NotBlank @Size(max = 80) String system,
            @NotBlank @Size(max = 2048) String url
    ) {
    }
}
