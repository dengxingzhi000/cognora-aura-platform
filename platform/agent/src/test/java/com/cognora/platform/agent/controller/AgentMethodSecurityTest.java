package com.cognora.platform.agent.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.dto.AssistanceFeedbackRequest;
import com.cognora.platform.agent.dto.AssistancePredictRequest;
import com.cognora.platform.agent.dto.WritingAssistanceRequest;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentMethodSecurityTest {

    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        context.close();
    }

    @Test
    void permissionAnnotationProtectsCapabilitiesAsSecondGate() {
        authenticate(List.of());
        AgentController controller = context.getBean(AgentController.class);

        assertThrows(AccessDeniedException.class, controller::capabilities);
    }

    @Test
    void matchingPermissionAllowsCapabilities() {
        authenticate(List.of(new SimpleGrantedAuthority("agent:capabilities:view")));
        AgentRuntimeClient client = context.getBean(AgentRuntimeClient.class);
        when(client.enterprise(any(), any(), any(), any(), any())).thenReturn(new RuntimeResponse(200, new ObjectMapper().createObjectNode()));
        when(client.browser(any(), any(), any(), any(), any())).thenReturn(new RuntimeResponse(200, new ObjectMapper().createObjectNode()));

        assertDoesNotThrow(() -> context.getBean(AgentController.class).capabilities());
    }

    @Test
    void writingAssistantRequiresDedicatedPermission() {
        authenticate(List.of(new SimpleGrantedAuthority("agent:chat:run")));
        WritingAssistantController controller = context.getBean(WritingAssistantController.class);

        assertThrows(AccessDeniedException.class, () -> controller.generate(request()));
    }

    @Test
    void matchingWritingPermissionAllowsGeneration() {
        authenticate(List.of(new SimpleGrantedAuthority("agent:writing:use")));
        AgentRuntimeClient client = context.getBean(AgentRuntimeClient.class);
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        when(client.enterprise(any(), any(), any(), any(), any())).thenReturn(
                new RuntimeResponse(200, mapper.createObjectNode().put("text", "商品描述")));

        assertDoesNotThrow(() -> context.getBean(WritingAssistantController.class).generate(request()));
    }

    private WritingAssistanceRequest request() {
        return new WritingAssistanceRequest("PRODUCT_DESCRIPTION", "GENERATE", "",
                java.util.Map.of("title", "背包"), 500, "zh-CN");
    }

    @Test
    void assistancePredictionRequiresDedicatedPermission() {
        authenticate(List.of());
        AgentController controller = context.getBean(AgentController.class);

        assertThrows(AccessDeniedException.class, () -> controller.predictAssistance(predictionRequest()));
    }

    @Test
    void assistanceFeedbackRequiresDedicatedPermission() {
        authenticate(List.of());
        AgentController controller = context.getBean(AgentController.class);
        AssistanceFeedbackRequest request = new AssistanceFeedbackRequest(
                true, true, false, "OPERATOR", true, "order-create-server-error");

        assertThrows(AccessDeniedException.class,
                () -> controller.feedbackAssistance("prediction-1", request));
    }

    @Test
    void assistanceModelStatusRequiresDedicatedPermission() {
        authenticate(List.of());
        AgentController controller = context.getBean(AgentController.class);

        assertThrows(AccessDeniedException.class, controller::assistanceModelStatus);
    }

    @Test
    void assistanceDashboardRequiresDedicatedPermission() {
        authenticate(List.of());
        AgentController controller = context.getBean(AgentController.class);

        assertThrows(AccessDeniedException.class, controller::assistanceDashboardData);
    }

    private AssistancePredictRequest predictionRequest() {
        AssistancePredictRequest.AssistanceSignals signals = new AssistancePredictRequest.AssistanceSignals(
                "order", "create_order", 300L, 240L, 60L, 20L, 4L, 2L, 3L, 4L, 2L, 1L,
                2L, 0L, new BigDecimal("0.7"), 1L, 30L, new BigDecimal("0.85"), 0L,
                false, false);
        return new AssistancePredictRequest("session-1", signals, java.util.List.of());
    }

    @Test
    void opsStatusRequiresDedicatedPermission() {
        authenticate(List.of(new SimpleGrantedAuthority("agent:chat:run")));
        OpsController controller = context.getBean(OpsController.class);

        assertThrows(AccessDeniedException.class, () -> controller.status("agent"));
    }

    @Test
    void matchingOpsPermissionAllowsStatus() {
        authenticate(List.of(new SimpleGrantedAuthority("ops:view")));
        AgentRuntimeClient client = context.getBean(AgentRuntimeClient.class);
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        when(client.enterprise(any(), any(), any(), any(), any())).thenReturn(
                new RuntimeResponse(200, mapper.createObjectNode()));

        assertDoesNotThrow(() -> context.getBean(OpsController.class).status("agent"));
    }

    private void authenticate(List<SimpleGrantedAuthority> authorities) {
        UUID userId = UUID.fromString("019a0aee-0000-7000-8000-000000000003");
        TenantContextHolder.setTenantId(UUID.fromString("019a0aee-0000-7000-8000-000000000002"));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("userId", userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, authorities));
    }

    @Configuration
    @EnableMethodSecurity
    static class TestConfiguration {
        @Bean
        AgentRuntimeClient agentRuntimeClient() {
            return mock(AgentRuntimeClient.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AgentController agentController(AgentRuntimeClient client, ObjectMapper objectMapper) {
            return new AgentController(client, objectMapper);
        }

        @Bean
        WritingAssistantController writingAssistantController(AgentRuntimeClient client, ObjectMapper objectMapper) {
            return new WritingAssistantController(client, objectMapper);
        }

        @Bean
        OpsController opsController(AgentRuntimeClient client, ObjectMapper objectMapper) {
            return new OpsController(client, objectMapper);
        }
    }
}
