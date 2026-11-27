package com.cognora.platform.agent.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cognora.agent")
public record AgentProperties(
        URI enterpriseBaseUrl,
        URI browserBaseUrl,
        String internalAppId,
        Duration connectTimeout,
        Duration readTimeout
) {
    public AgentProperties {
        if (enterpriseBaseUrl == null || browserBaseUrl == null) {
            throw new IllegalArgumentException("Agent runtime base URLs are required");
        }
        if (internalAppId == null || internalAppId.isBlank()) {
            throw new IllegalArgumentException("cognora.agent.internal-app-id is required");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("cognora.agent.connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("cognora.agent.read-timeout must be positive");
        }
    }
}
