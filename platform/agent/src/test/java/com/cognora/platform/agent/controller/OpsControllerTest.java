package com.cognora.platform.agent.controller;

import java.util.Map;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.exception.AgentExceptionHandler;
import com.cognora.platform.common.tenant.TenantContextHolder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpsControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000003");

    private final AgentRuntimeClient client = mock(AgentRuntimeClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OpsController(client, objectMapper))
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
    void statusUsesRuntimeAndTrustedIdentity() throws Exception {
        when(client.enterprise(eq("GET"), eq("/ops/status"), eq("profile=agent"), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree(
                        "{\"applied\":false,\"services\":[{\"name\":\"web\",\"state\":\"running\",\"health\":\"UP\"}]}")));

        mvc.perform(get("/api/v1/agent/ops/status").param("profile", "agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.services[0].name").value("web"));

        verify(client).enterprise(eq("GET"), eq("/ops/status"), eq("profile=agent"), isNull(),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        TENANT_ID.toString().equals(identity.tenantId())
                                && USER_ID.toString().equals(identity.userId())));
    }

    @Test
    void actionForwardsExplicitApplyFlag() throws Exception {
        when(client.enterprise(eq("POST"), eq("/ops/actions"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree(
                        "{\"applied\":false,\"output\":[\"DRY-RUN restart\"]}")));

        mvc.perform(post("/api/v1/agent/ops/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"RESTART","profile":"agent","apply":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));

        verify(client).enterprise(eq("POST"), eq("/ops/actions"), isNull(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(payload ->
                        "RESTART".equals(payload.get("action"))
                                && "agent".equals(payload.get("profile"))
                                && Boolean.FALSE.equals(payload.get("apply"))), any());
    }

    @Test
    void invalidActionIsRejectedBeforeRuntimeCall() throws Exception {
        mvc.perform(post("/api/v1/agent/ops/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"DELETE_ALL","apply":true}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test
    void runtimeAuthenticationFailureIsGatewayFailure() throws Exception {
        when(client.enterprise(any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeResponse(401, objectMapper.createObjectNode()));

        mvc.perform(get("/api/v1/agent/ops/catalog"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("运维控制面暂时不可用"));
    }
}
