package com.cognora.platform.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AssistanceFeedbackRequest(
        @NotNull @JsonProperty("needed_assistance") Boolean neededAssistance,
        @NotNull @JsonProperty("assistance_used") Boolean assistanceUsed,
        @NotNull @JsonProperty("task_succeeded") Boolean taskSucceeded,
        @NotBlank @Pattern(regexp = "OPERATOR|OUTCOME|REVIEWER") String source,
        @JsonProperty("knowledge_helpful") Boolean knowledgeHelpful,
        @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+")
        @JsonProperty("knowledge_article_id") String knowledgeArticleId
) {
}
