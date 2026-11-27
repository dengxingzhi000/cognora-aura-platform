package com.cognora.platform.agent.controller;

import java.util.Map;
import java.util.UUID;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.client.AgentRuntimeClient.RuntimeResponse;
import com.cognora.platform.agent.exception.AgentExceptionHandler;
import com.cognora.platform.agent.exception.RuntimeCallException;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

class AgentControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("019a0aee-0000-7000-8000-000000000003");
    private static final String ASSISTANCE_PAYLOAD = """
            {
              "session_id": "session-1",
              "signals": {
                "module": "order",
                "workflow": "create_order",
                "session_duration_seconds": 300,
                "active_seconds": 240,
                "idle_seconds": 60,
                "event_count": 20,
                "error_count": 4,
                "unique_error_count": 2,
                "repeated_error_count": 3,
                "retry_count": 4,
                "back_navigation_count": 2,
                "context_switch_count": 1,
                "validation_failure_count": 2,
                "permission_denied_count": 0,
                "form_completion_ratio": 0.7,
                "help_open_count": 1,
                "previous_task_count": 30,
                "previous_success_rate": 0.85,
                "recent_assistance_count": 0,
                "explicit_help_request": false,
                "high_risk_action": false
              }
            }
            """;

    private final AgentRuntimeClient client = mock(AgentRuntimeClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(client, objectMapper))
                .setControllerAdvice(new AgentExceptionHandler())
                .build();
        TenantContextHolder.setTenantId(TENANT_ID);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("userId", USER_ID.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_DEPARTMENT_MANAGER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void chatUsesTrustedTenantUserAndRole() throws Exception {
        when(client.enterprise(eq("POST"), eq("/chat"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("{\"status\":\"done\",\"reply\":\"ok\"}")));

        mvc.perform(post("/api/v1/agent/chat")
                        .header("Authorization", "Bearer user-jwt")
                        .header("X-Device-ID", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"tenant_id\":\"attacker\",\"user_id\":\"attacker\",\"assistance_prediction_id\":\"prediction-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("done"));

        verify(client).enterprise(eq("POST"), eq("/chat"), isNull(), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(payload ->
                TENANT_ID.toString().equals(payload.get("tenant_id"))
                        && USER_ID.toString().equals(payload.get("user_id"))
                        && "manager".equals(payload.get("role"))
                        && "prediction-1".equals(payload.get("assistance_prediction_id"))
                        && !payload.containsKey("tenantId")), org.mockito.ArgumentMatchers.argThat(identity ->
                TENANT_ID.toString().equals(identity.tenantId())
                        && USER_ID.toString().equals(identity.userId())
                        && "manager".equals(identity.role())
                        && "user-jwt".equals(identity.platformToken())
                        && "device-1".equals(identity.deviceId())));
    }

    @Test
    void browserTaskCannotSpoofTenantOrUser() throws Exception {
        when(client.browser(eq("POST"), eq("/api/v1/browser/tasks"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(201, objectMapper.readTree("{\"taskId\":\"task-1\",\"status\":\"PENDING\"}")));

        mvc.perform(post("/api/v1/agent/browser/tasks")
                        .header("Idempotency-Key", "idem-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskType":"product.create","target":{"system":"product","url":"http://localhost:3000/product"},
                                 "input":{"tenantId":"attacker","userId":"attacker"},"executionMode":"require_approval"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.taskId").value("task-1"));

        verify(client).browser(eq("POST"), eq("/api/v1/browser/tasks"), isNull(), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(payload ->
                TENANT_ID.toString().equals(payload.get("tenantId"))
                        && USER_ID.toString().equals(payload.get("userId"))
                        && "require_approval".equals(payload.get("executionMode"))), org.mockito.ArgumentMatchers.argThat(identity ->
                TENANT_ID.toString().equals(identity.tenantId())
                        && USER_ID.toString().equals(identity.userId())
                        && "idem-123".equals(identity.idempotencyKey())));
    }

    @Test
    void capabilitiesRemainPartiallyAvailableWhenEnterpriseRuntimeIsDown() throws Exception {
        when(client.enterprise(eq("GET"), eq("/capabilities"), isNull(), isNull(), any()))
                .thenThrow(new RuntimeCallException("down", java.time.Duration.ofSeconds(1), null));
        when(client.browser(eq("GET"), eq("/api/v1/browser/tools"), isNull(), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("{\"tools\":[{\"name\":\"click\"}]}")));
        when(client.browser(eq("GET"), eq("/api/v1/browser/workflows"), isNull(), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("{\"workflows\":[{\"name\":\"product.create\"}]}")));

        mvc.perform(get("/api/v1/agent/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enterprise.tools.length()").value(0))
                .andExpect(jsonPath("$.data.browser.tools[0].name").value("click"))
                .andExpect(jsonPath("$.data.browser.workflows[0].name").value("product.create"));
    }

    @Test
    void invalidPaginationIsRejectedBeforeCallingRuntime() throws Exception {
        mvc.perform(get("/api/v1/agent/browser/tasks").param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsafeRuntimePathIdIsRejected() throws Exception {
        mvc.perform(get("/api/v1/agent/browser/tasks/bad!"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runtimeAuthenticationFailureDoesNotLeakAsCallerAuthorizationFailure() throws Exception {
        when(client.browser(any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeResponse(401, objectMapper.readTree("{\"error\":\"secret detail\"}")));

        mvc.perform(get("/api/v1/agent/browser/tasks/task-1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Agent runtime rejected the request"));
    }

    @Test
    void unavailableRuntimeReturnsRetryableGatewayError() throws Exception {
        when(client.browser(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeCallException("connection refused", java.time.Duration.ofSeconds(3), null));

        mvc.perform(get("/api/v1/agent/browser/tasks/task-1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502));
    }

    @Test
    void assistancePredictionForwardsValidatedSignalsAndTrustedIdentity() throws Exception {
        when(client.enterprise(eq("POST"), eq("/assistance/predict"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("""
                        {"prediction_id":"prediction-1","probability":0.91,"decision":"OFFER_ASSISTANCE"}
                        """)));

        mvc.perform(post("/api/v1/agent/assistance/predict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ASSISTANCE_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("OFFER_ASSISTANCE"));

        verify(client).enterprise(eq("POST"), eq("/assistance/predict"), isNull(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(payload -> {
                    if (!"session-1".equals(payload.get("session_id"))
                            || !(payload.get("signals") instanceof Map<?, ?> signals)) {
                        return false;
                    }
                    return ((Number) signals.get("error_count")).longValue() == 4
                            && Boolean.FALSE.equals(signals.get("explicit_help_request"))
                            && !payload.containsKey("tenant_id")
                            && !payload.containsKey("user_id");
                }),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        TENANT_ID.toString().equals(identity.tenantId())
                                && USER_ID.toString().equals(identity.userId())));
    }

    @Test
    void assistancePredictionForwardsRecentEventSequence() throws Exception {
        when(client.enterprise(eq("POST"), eq("/assistance/predict"), isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("{\"decision\":\"DO_NOT_INTERRUPT\"}")));
        String withEvents = ASSISTANCE_PAYLOAD.replace("\"signals\": {", """
                "recent_events": [
                  {"type":"API_ERROR","offset_ms":1000,"duration_ms":0,"severity":2,"error_code":"SERVER_ERROR"},
                  {"type":"RETRY","offset_ms":2000,"duration_ms":0,"severity":0,"error_code":null}
                ],
                "signals": {""");

        mvc.perform(post("/api/v1/agent/assistance/predict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withEvents))
                .andExpect(status().isOk());

        verify(client).enterprise(eq("POST"), eq("/assistance/predict"), isNull(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(payload ->
                        payload.get("recent_events") instanceof java.util.List<?> events
                                && events.size() == 2
                                && events.get(0) instanceof Map<?, ?> event
                                && "API_ERROR".equals(event.get("type"))
                                && ((Number) event.get("offset_ms")).longValue() == 1000),
                any());
    }

    @Test
    void assistanceModelStatusUsesDedicatedRuntimePath() throws Exception {
        when(client.enterprise(eq("GET"), eq("/assistance/model/status"), isNull(), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("""
                        {"model_version":"bootstrap-v2","live_count":3}
                        """)));

        mvc.perform(get("/api/v1/agent/assistance/model/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model_version").value("bootstrap-v2"));

        verify(client).enterprise(eq("GET"), eq("/assistance/model/status"), isNull(), isNull(), any());
    }

    @Test
    void assistanceDashboardUsesDedicatedRuntimePath() throws Exception {
        when(client.enterprise(eq("GET"), eq("/assistance/dashboard/data"), isNull(), isNull(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("""
                        {"coverage":{"feedback_coverage":0.5},"knowledge":{"helpful_rate":0.8}}
                        """)));

        mvc.perform(get("/api/v1/agent/assistance/dashboard/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.feedback_coverage").value(0.5));

        verify(client).enterprise(eq("GET"), eq("/assistance/dashboard/data"), isNull(), isNull(), any());
    }

    @Test
    void assistancePredictionRejectsOutOfRangeSignalsBeforeRuntimeCall() throws Exception {
        String invalid = ASSISTANCE_PAYLOAD.replace("\"form_completion_ratio\": 0.7",
                "\"form_completion_ratio\": 1.1");

        mvc.perform(post("/api/v1/agent/assistance/predict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(client);
    }

    @Test
    void assistanceFeedbackUsesTrustedIdentityAndSafeRuntimePath() throws Exception {
        when(client.enterprise(eq("POST"), eq("/assistance/predictions/prediction-1/feedback"),
                isNull(), any(), any()))
                .thenReturn(new RuntimeResponse(200, objectMapper.readTree("{\"prediction_id\":\"prediction-1\"}")));

        mvc.perform(post("/api/v1/agent/assistance/predictions/prediction-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"needed_assistance":true,"assistance_used":true,
                                 "task_succeeded":false,"source":"OPERATOR",
                                 "knowledge_helpful":true,"knowledge_article_id":"order-create-server-error"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prediction_id").value("prediction-1"));

        verify(client).enterprise(eq("POST"), eq("/assistance/predictions/prediction-1/feedback"),
                isNull(), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(payload ->
                        Boolean.TRUE.equals(payload.get("needed_assistance"))
                                && "OPERATOR".equals(payload.get("source"))
                                && Boolean.TRUE.equals(payload.get("knowledge_helpful"))
                                && "order-create-server-error".equals(payload.get("knowledge_article_id"))),
                org.mockito.ArgumentMatchers.argThat(identity -> USER_ID.toString().equals(identity.userId())));
    }

    @Test
    void assistanceModelUnavailableKeepsRetryableServiceUnavailableStatus() throws Exception {
        when(client.enterprise(any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeResponse(503, objectMapper.readTree("{\"code\":\"ASSISTANCE_MODEL_UNAVAILABLE\"}")));

        mvc.perform(post("/api/v1/agent/assistance/predict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ASSISTANCE_PAYLOAD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }
}
