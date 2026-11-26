package com.cognora.platform.agent.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.cognora.platform.common.constant.HttpHeaderConstants;
import com.cognora.platform.agent.config.AgentProperties;
import com.cognora.platform.agent.exception.RuntimeCallException;
import com.cognora.platform.agent.dto.AgentIdentity;
import com.cognora.platform.common.rest.signature.InternalSignatureSecretResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class AgentRuntimeClient {

    private static final String SIGNATURE_ALGORITHM = "HmacSHA256";

    private final AgentProperties properties;
    private final InternalSignatureSecretResolver secretResolver;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String secret;

    public AgentRuntimeClient(AgentProperties properties,
                              InternalSignatureSecretResolver secretResolver,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
        this.secret = secretResolver.resolveSecret();
        if (secret.length() < 32) {
            throw new IllegalStateException("API_SECRET_INTERNAL_SERVICE must contain at least 32 characters");
        }
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public RuntimeResponse enterprise(String method, String path, String query, Object body, AgentIdentity identity) {
        return send(properties.enterpriseBaseUrl(), method, path, query, body, identity, true);
    }

    public RuntimeResponse browser(String method, String path, String query, Object body, AgentIdentity identity) {
        return send(properties.browserBaseUrl(), method, path, query, body, identity, false);
    }

    public BinaryRuntimeResponse browserBinary(String path, AgentIdentity identity) {
        HttpRequest request = buildRequest(properties.browserBaseUrl(), "GET", path, null, null, identity, false);
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new BinaryRuntimeResponse(response.statusCode(), response.body(), response.headers().map());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeCallException("Agent runtime call interrupted", Duration.ofSeconds(2), e, true);
        } catch (IOException e) {
            boolean statusUnknown = !(e instanceof java.net.http.HttpConnectTimeoutException);
            throw new RuntimeCallException("Agent runtime unavailable", properties.readTimeout(), e, statusUnknown);
        }
    }

    private RuntimeResponse send(URI baseUrl, String method, String path, String query,
                                 Object body, AgentIdentity identity, boolean includePlatformToken) {
        HttpRequest request = buildRequest(baseUrl, method, path, query, body, identity, includePlatformToken);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode responseBody = readJson(response.body());
            return new RuntimeResponse(response.statusCode(), responseBody,
                    !"INVALID_RUNTIME_RESPONSE".equals(responseBody.path("code").asText()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeCallException("Agent runtime call interrupted", Duration.ofSeconds(2), e, true);
        } catch (IOException e) {
            boolean statusUnknown = !(e instanceof java.net.http.HttpConnectTimeoutException);
            throw new RuntimeCallException("Agent runtime unavailable", properties.readTimeout(), e, statusUnknown);
        }
    }

    private HttpRequest buildRequest(URI baseUrl, String method, String path, String query,
                                     Object body, AgentIdentity identity, boolean includePlatformToken) {
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        String jsonBody = body == null ? "" : writeJson(body);
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String platformToken = includePlatformToken ? identity.platformToken() : null;
        String tokenHash = platformToken == null || platformToken.isBlank() ? "" : sha256(platformToken);
        String idempotencyKey = identity.idempotencyKey() == null ? "" : identity.idempotencyKey();
        String deviceId = includePlatformToken && identity.deviceId() != null ? identity.deviceId() : "";
        String payload = canonicalPayload(
                normalizedMethod, path, query, jsonBody, timestamp, nonce,
                properties.internalAppId(), identity.tenantId(), identity.userId(), identity.role(),
                tokenHash, deviceId, idempotencyKey);
        String signature = hmac(payload);

        String target = trimTrailingSlash(baseUrl.toString()) + path
                + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(HttpHeaderConstants.SIGNATURE_APP_ID, properties.internalAppId())
                .header(HttpHeaderConstants.SIGNATURE_TIMESTAMP, timestamp)
                .header(HttpHeaderConstants.SIGNATURE_NONCE, nonce)
                .header(HttpHeaderConstants.SIGNATURE, signature)
                .header(HttpHeaderConstants.TENANT_ID, identity.tenantId())
                .header(HttpHeaderConstants.USER_ID, identity.userId())
                .header(HttpHeaderConstants.USER_ROLE, identity.role());
        if (platformToken != null && !platformToken.isBlank()) {
            builder.header(HttpHeaderConstants.PLATFORM_TOKEN, platformToken);
        }
        if (!deviceId.isBlank()) {
            builder.header(HttpHeaderConstants.DEVICE_ID, deviceId);
        }
        if (!idempotencyKey.isBlank()) {
            builder.header(HttpHeaderConstants.IDEMPOTENCY_KEY, idempotencyKey);
        }

        return switch (normalizedMethod) {
            case "GET" -> builder.GET().build();
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
            case "DELETE" -> builder.DELETE().build();
            default -> throw new IllegalArgumentException("Unsupported method: " + normalizedMethod);
        };
    }

    static String canonicalPayload(
            String method, String path, String query, String body,
            String timestamp, String nonce, String appId, String tenantId,
            String userId, String role, String tokenHash, String deviceId,
            String idempotencyKey) {
        query = query == null ? "" : query;
        String canonicalQuery = canonicalQuery(query);
        String[] signedFields = {
                method, path, query, timestamp, nonce, appId, tenantId,
                userId, role, tokenHash, deviceId, idempotencyKey
        };
        for (String field : signedFields) {
            if (field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Internal signature fields must not contain newlines");
            }
        }
        return String.join("\n",
                method.toUpperCase(Locale.ROOT),
                path,
                canonicalQuery,
                sha256(body),
                timestamp,
                nonce,
                appId,
                tenantId,
                userId,
                role,
                tokenHash,
                deviceId,
                idempotencyKey) + "\n";
    }

    static String canonicalQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        return java.util.Arrays.stream(query.split("&"))
                .filter(part -> !part.isEmpty())
                .sorted()
                .collect(Collectors.joining("&"));
    }

    static String hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(SIGNATURE_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), SIGNATURE_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign agent runtime request", e);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize agent request", e);
        }
    }

    private JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("code", "INVALID_RUNTIME_RESPONSE");
            return error;
        }
    }

    private String hmac(String payload) {
        return hmac(payload, secret);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record BinaryRuntimeResponse(int status, byte[] body, Map<String, List<String>> headers) {
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    public record RuntimeResponse(int status, JsonNode body, boolean valid) {
        public RuntimeResponse(int status, JsonNode body) {
            this(status, body, true);
        }

        public boolean isSuccess() {
            return valid && status >= 200 && status < 300;
        }
    }
}
