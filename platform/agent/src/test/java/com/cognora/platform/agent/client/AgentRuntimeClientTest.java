package com.cognora.platform.agent.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.cognora.platform.agent.config.AgentProperties;
import com.cognora.platform.agent.dto.AgentIdentity;
import com.cognora.platform.common.rest.signature.InternalSignatureSecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeClientTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void canonicalSignatureMatchesOtherRuntimes() {
        String payload = AgentRuntimeClient.canonicalPayload(
                "post", "/chat", "b=2&a=1", "{\"message\":\"hi\"}",
                "1700000000000", "nonce-123", "platform-agent", "tenant-1", "user-1", "admin",
                AgentRuntimeClient.sha256("user-jwt"), "device-1", "idem-1");

        assertEquals("39aabe757f716509b1406f13cf6a361825e8392e4bea0494ad26b1fe7c88d708",
                AgentRuntimeClient.hmac(payload, SECRET));
        assertEquals("a=1&b=2", AgentRuntimeClient.canonicalQuery("b=2&a=1"));
    }

    @Test
    void emptyBodyAndOptionalFieldsMatchPythonFixedVector() {
        String payload = AgentRuntimeClient.canonicalPayload(
                "GET", "/chat", "b=2&&a=1&", "",
                "1700000000000", "nonce-123", "platform-agent", "tenant-1", "user-1", "admin",
                "", "", "");

        assertEquals("GET\n/chat\na=1&b=2\n"
                + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n"
                + "1700000000000\nnonce-123\nplatform-agent\ntenant-1\nuser-1\nadmin\n\n\n\n", payload);
        assertEquals("a1bc05fc3df040b1e1a864c7b4472485a9c95711a822d25689aa42a47bf13c4e",
                AgentRuntimeClient.hmac(payload, SECRET));
    }

    @Test
    void canonicalPayloadRejectsNewlinesInEverySignedField() {
        String[] fields = {
                "GET", "/chat", "a=1", "", "1700000000000", "nonce", "app",
                "tenant", "user", "admin", "", "device", "idem"
        };
        for (int i = 0; i < fields.length; i++) {
            if (i == 3) {
                continue; // Body bytes are hashed, so newlines cannot inject payload fields.
            }
            String[] tampered = fields.clone();
            tampered[i] += (i % 2 == 0 ? "\n" : "\r");
            String[] values = tampered;
            assertThrows(IllegalArgumentException.class, () -> AgentRuntimeClient.canonicalPayload(
                    values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                    values[7], values[8], values[9], values[10], values[11], values[12]));
        }
    }

    @Test
    void refusesToStartWithoutStrongSharedSecret() {
        InternalSignatureSecretResolver resolver = mock(InternalSignatureSecretResolver.class);
        when(resolver.resolveSecret()).thenReturn("short");

        assertThrows(IllegalStateException.class, () -> new AgentRuntimeClient(properties(URI.create("http://127.0.0.1:1")), resolver, new ObjectMapper()));
    }

    @Test
    void sendsSignedTrustedIdentityAndPlatformTokenWithoutForwardingAuthorization() throws Exception {
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            headers.set(Map.of(
                    "app", exchange.getRequestHeaders().getFirst("X-App-Id"),
                    "tenant", exchange.getRequestHeaders().getFirst("X-Tenant-Id"),
                    "user", exchange.getRequestHeaders().getFirst("X-User-Id"),
                    "role", exchange.getRequestHeaders().getFirst("X-User-Role"),
                    "platformToken", String.valueOf(exchange.getRequestHeaders().getFirst("X-Platform-Token")),
                    "device", String.valueOf(exchange.getRequestHeaders().getFirst("X-Device-ID")),
                    "idempotency", String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "signature", exchange.getRequestHeaders().getFirst("X-Signature")));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"done\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            InternalSignatureSecretResolver resolver = mock(InternalSignatureSecretResolver.class);
            when(resolver.resolveSecret()).thenReturn(SECRET);
            AgentRuntimeClient client = new AgentRuntimeClient(
                    properties(URI.create("http://127.0.0.1:" + server.getAddress().getPort())), resolver, new ObjectMapper());
            AgentIdentity identity = new AgentIdentity("tenant-1", "user-1", "manager", "user-jwt", "device-1", "idem-1");

            AgentRuntimeClient.RuntimeResponse response = client.enterprise("POST", "/chat", null,
                    Map.of("message", "hi", "tenant_id", "spoofed"), identity);

            assertTrue(response.isSuccess());
            assertEquals("done", response.body().path("status").asText());
            assertEquals("platform-agent", headers.get().get("app"));
            assertEquals("tenant-1", headers.get().get("tenant"));
            assertEquals("user-1", headers.get().get("user"));
            assertEquals("manager", headers.get().get("role"));
            assertEquals("user-jwt", headers.get().get("platformToken"));
            assertEquals("device-1", headers.get().get("device"));
            assertEquals("idem-1", headers.get().get("idempotency"));
            assertEquals("null", headers.get().get("authorization"));
            assertFalse(headers.get().get("signature").isBlank());
            assertTrue(requestBody.get().contains("spoofed"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void browserCallsNeverReceivePlatformCredentials() throws Exception {
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/browser/tasks", exchange -> {
            headers.set(Map.of(
                    "token", String.valueOf(exchange.getRequestHeaders().getFirst("X-Platform-Token")),
                    "device", String.valueOf(exchange.getRequestHeaders().getFirst("X-Device-ID")),
                    "idempotency", String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key"))));
            byte[] response = "{\"records\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            InternalSignatureSecretResolver resolver = mock(InternalSignatureSecretResolver.class);
            when(resolver.resolveSecret()).thenReturn(SECRET);
            AgentRuntimeClient client = new AgentRuntimeClient(
                    properties(URI.create("http://127.0.0.1:" + server.getAddress().getPort())), resolver, new ObjectMapper());
            client.browser("GET", "/api/v1/browser/tasks", null, null,
                    new AgentIdentity("tenant-1", "user-1", "admin", "user-jwt", "device-1", null));
            assertEquals("null", headers.get().get("token"));
            assertEquals("null", headers.get().get("device"));
        } finally {
            server.stop(0);
        }
    }

    private AgentProperties properties(URI baseUrl) {
        return new AgentProperties(baseUrl, baseUrl, "platform-agent", Duration.ofSeconds(1), Duration.ofSeconds(3));
    }
}
