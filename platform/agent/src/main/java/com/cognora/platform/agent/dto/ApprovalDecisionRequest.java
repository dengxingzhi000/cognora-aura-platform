package com.cognora.platform.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ApprovalDecisionRequest(
        @NotNull @Pattern(regexp = "approve|reject") String decision
) {
}
