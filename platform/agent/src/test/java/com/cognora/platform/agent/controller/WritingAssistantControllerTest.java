package com.cognora.platform.agent.controller;

import java.util.Map;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.exception.AgentExceptionHandler;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WritingAssistantControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000003");

    private final AgentRuntimeClient client = mock(AgentRuntimeClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WritingAssistantController(client, objectMapper))
                .setControllerAdvice(new AgentExceptionHandler())
                .build();
        TenantContextHolder.setTenantId(TENANT_ID);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("userId", USER_ID.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void forwardsWritingRequestWithTrustedIdentity() throws Exception {
        when(client.enterprise(eq("POST"), eq("/writing-assistant"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree(
                        "{\"text\":\"轻便耐用，适合日常通勤。\",\"scene\":\"PRODUCT_DESCRIPTION\","
                                + "\"action\":\"GENERATE\",\"model\":\"deepseek-chat\",\"usage\":{\"total_tokens\":128}}")));

        mvc.perform(post("/api/v1/agent/writing-assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scene":"PRODUCT_DESCRIPTION","action":"GENERATE","content":"",
                                 "context":{"title":"城市通勤双肩包","brand":"Aura","empty":" "},"maxLength":500,
                                 "language":"zh-CN","tenant_id":"attacker","user_id":"attacker"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("轻便耐用，适合日常通勤。"));

        verify(client).enterprise(eq("POST"), eq("/writing-assistant"), isNull(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(payload ->
                        "PRODUCT_DESCRIPTION".equals(payload.get("scene"))
                                && "GENERATE".equals(payload.get("action"))
                                && Integer.valueOf(500).equals(payload.get("max_length"))
                                && "zh-CN".equals(payload.get("language"))
                                && !((Map<?, ?>) payload.get("context")).containsKey("empty")
                                && !payload.containsKey("tenant_id")
                                && !payload.containsKey("user_id")),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        TENANT_ID.toString().equals(identity.tenantId())
                                && USER_ID.toString().equals(identity.userId())));
    }

    @Test
    void rejectsPolishWithoutContentBeforeCallingRuntime() throws Exception {
        mvc.perform(post("/api/v1/agent/writing-assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scene":"PRODUCT_DESCRIPTION","action":"POLISH","content":" ",
                                 "context":{"title":"背包"}}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(client);
    }

    @Test
    void rejectsUnsupportedSceneBeforeCallingRuntime() throws Exception {
        mvc.perform(post("/api/v1/agent/writing-assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scene":"SYSTEM_ADMIN","action":"GENERATE","context":{"title":"背包"}}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(client);
    }

    @Test
    void preservesRateLimitStatusWithoutRuntimeDetails() throws Exception {
        when(client.enterprise(any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeResponse(429, objectMapper.readTree(
                        "{\"detail\":{\"code\":\"WRITING_RATE_LIMITED\",\"secret\":\"internal\"}}")));

        mvc.perform(post("/api/v1/agent/writing-assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scene":"PRODUCT_DESCRIPTION","action":"GENERATE","context":{"title":"背包"}}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("AI 生成过于频繁，请稍后再试"));
    }

    @Test
    void distinguishesRuntimeValidationFromSensitiveContentRejection() throws Exception {
        when(client.enterprise(any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeResponse(422, objectMapper.readTree(
                        "{\"detail\":{\"code\":\"VALIDATION_ERROR\"}}")));

        mvc.perform(post("/api/v1/agent/writing-assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scene":"PRODUCT_DESCRIPTION","action":"GENERATE","context":{"title":"背包"}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("请求内容不符合 AI 写作助手要求"));
    }

    @Test
    void mapsUnavailableWritingRuntimeToServiceUnavailable() throws Exception {
        when(client.enterprise(any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeResponse(503, objectMapper.createObjectNode()));

        mvc.perform(post("/api/v1/agent/writing-assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scene":"PRODUCT_DESCRIPTION","action":"GENERATE","context":{"title":"背包"}}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void rejectsMalformedRuntimeSuccess() throws Exception {
        when(client.enterprise(any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("{\"text\":\"\"}")));

        mvc.perform(post("/api/v1/agent/writing-assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scene":"PRODUCT_DESCRIPTION","action":"GENERATE","context":{"title":"背包"}}
                                """))
                .andExpect(status().isBadGateway());
    }
}
