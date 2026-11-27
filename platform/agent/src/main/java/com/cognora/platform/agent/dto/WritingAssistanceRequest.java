package com.cognora.platform.agent.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WritingAssistanceRequest(
        @NotBlank @Pattern(regexp = "PRODUCT_DESCRIPTION|SUPPLIER_REMARK|ORDER_NOTE|GENERAL_DESCRIPTION") String scene,
        @NotBlank @Pattern(regexp = "GENERATE|POLISH|REWRITE|SHORTEN") String action,
        @Size(max = 4000) String content,
        @Size(max = 12) Map<@NotNull @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,39}") String,
                @NotNull @Size(max = 500) String> context,
        @Min(50) @Max(2000) Integer maxLength,
        @Pattern(regexp = "zh-CN|en-US") String language
) {
    public WritingAssistanceRequest {
        content = content == null ? "" : content;
        Map<String, String> normalizedContext = new LinkedHashMap<>();
        if (context != null) {
            context.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    normalizedContext.put(key, value.trim());
                }
            });
        }
        context = Collections.unmodifiableMap(normalizedContext);
        maxLength = maxLength == null ? 500 : maxLength;
        language = language == null || language.isBlank() ? "zh-CN" : language;
    }

    @AssertTrue(message = "润色、改写或压缩时必须提供原有内容")
    public boolean isContentValid() {
        return "GENERATE".equals(action) || !content.isBlank();
    }

    @AssertTrue(message = "生成时必须提供上下文或原有内容")
    public boolean isSourcePresent() {
        return !"GENERATE".equals(action) || !content.isBlank() || !context.isEmpty();
    }
}
