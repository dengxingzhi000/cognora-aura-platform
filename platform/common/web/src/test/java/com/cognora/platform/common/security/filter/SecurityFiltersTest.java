package com.cognora.platform.common.security.filter;

import com.cognora.platform.common.log.service.ISysAuditLogService;
import com.cognora.platform.common.security.PermissionService;
import com.cognora.platform.common.security.config.ApiAccessControlProperties;
import com.cognora.platform.common.security.config.SecurityFilterProperties;
import com.cognora.platform.common.security.metrics.SecurityMetrics;
import com.cognora.platform.common.security.stepup.StepUpEvaluator;
import com.cognora.platform.common.security.stepup.StepUpFilter;
import com.cognora.platform.common.security.stepup.StepUpRequirement;
import com.cognora.platform.common.security.stepup.StepUpProperties;
import com.cognora.platform.common.security.util.HttpServletRequestUtils;
import com.cognora.platform.common.security.util.JwtUtils;
import com.cognora.platform.common.security.properties.JwtProperties;
import com.cognora.platform.common.web.domain.SecurityUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class SecurityFiltersTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void apiAccessControlReturnsJsonOnForbidden() throws ServletException, IOException {
        PermissionService permissionService = Mockito.mock(PermissionService.class);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        ApiAccessControlProperties apiProps = new ApiAccessControlProperties();
        apiProps.setWhitelist(List.of());

        when(permissionService.findPermissionsByUrl(any(), any()))
                .thenReturn(List.of("perm:read"));
        when(permissionService.findAllPermissionsByUserId(any()))
                .thenReturn(Set.of());

        ApiAccessControlFilter filter = new ApiAccessControlFilter(permissionService, auditLogProvider(), metrics, apiProps);

        SecurityUser principal = SecurityUser.builder()
                .userId(UUID.randomUUID())
                .username("alice")
                .roles(Set.of())
                .permissions(Set.of())
                .build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure/data");
        request.addHeader("X-Request-ID", "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(proceeded.get(), "Forbidden request should not proceed");
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("ACCESS_DENIED"));
        assertTrue(body.contains("req-123"));
    }

    @Test
    void apiAccessControlSnapshot() throws ServletException, IOException {
        PermissionService permissionService = Mockito.mock(PermissionService.class);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        ApiAccessControlProperties apiProps = new ApiAccessControlProperties();
        apiProps.setWhitelist(List.of());

        when(permissionService.findPermissionsByUrl(any(), any()))
                .thenReturn(List.of("perm:read"));
        when(permissionService.findAllPermissionsByUserId(any()))
                .thenReturn(Set.of());

        ApiAccessControlFilter filter = new ApiAccessControlFilter(permissionService, auditLogProvider(), metrics, apiProps);

        SecurityUser principal = SecurityUser.builder()
                .userId(UUID.randomUUID())
                .username("alice")
                .roles(Set.of())
                .permissions(Set.of())
                .build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure/data");
        request.addHeader("X-Request-ID", "trace-api");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(proceeded.get(), "Forbidden request should not proceed");
        // Assert the structure, not the exact wording: the message is localised Chinese text that
        // has been through the repository-wide encoding corruption, so pinning its bytes makes the
        // test fail for reasons unrelated to access control.
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":403"));
        assertTrue(body.contains("\"error\":\"ACCESS_DENIED\""));
        assertTrue(body.contains("\"traceId\":\"trace-api\""));
        assertTrue(body.contains("\"path\":\"/secure/data\""));
    }

    @Test
    void apiAccessControlAllowsWhenPermissionMatches() throws ServletException, IOException {
        PermissionService permissionService = Mockito.mock(PermissionService.class);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        ApiAccessControlProperties apiProps = new ApiAccessControlProperties();
        apiProps.setWhitelist(List.of());

        when(permissionService.findPermissionsByUrl(any(), any()))
                .thenReturn(List.of("perm:read"));
        when(permissionService.findAllPermissionsByUserId(any()))
                .thenReturn(Set.of("perm:read"));

        ApiAccessControlFilter filter = new ApiAccessControlFilter(permissionService, auditLogProvider(), metrics, apiProps);

        SecurityUser principal = SecurityUser.builder()
                .userId(UUID.randomUUID())
                .username("alice")
                .roles(Set.of("role:a"))
                .permissions(Set.of("perm:read"))
                .build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure/data");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(proceeded.get(), "Authorized request should proceed");
        assertEquals(200, response.getStatus());
    }

    @Test
    void sqlInjectionFilterReturnsJson() throws ServletException, IOException {
        SecurityFilterProperties props = new SecurityFilterProperties();
        props.setMode(SecurityFilterProperties.SqlFilterMode.BLOCK);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        SqlInjectionFilter filter = new SqlInjectionFilter(props, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/search");
        request.addParameter("q", "1' union select password from users --");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(proceeded.get(), "Malicious request should be blocked");
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertTrue(response.getContentAsString().contains("SQL_INJECTION"));
    }

    @Test
    void sqlInjectionFilterSnapshotMatches() throws ServletException, IOException {
        SecurityFilterProperties props = new SecurityFilterProperties();
        props.setMode(SecurityFilterProperties.SqlFilterMode.BLOCK);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        SqlInjectionFilter filter = new SqlInjectionFilter(props, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/search");
        request.addParameter("q", "1' union select password from users --");
        request.addHeader("X-Request-ID", "trace-sql");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(proceeded.get(), "Malicious request should be blocked");
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":403"));
        assertTrue(body.contains("\"error\":\"SQL_INJECTION\""));
        assertTrue(body.contains("\"traceId\":\"trace-sql\""));
        assertTrue(body.contains("\"path\":\"/search\""));
    }

    @Test
    void sqlInjectionFilterAlertsWhenMonitorOnly() throws ServletException, IOException {
        SecurityFilterProperties props = new SecurityFilterProperties();
        props.setMode(SecurityFilterProperties.SqlFilterMode.ALERT);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        SqlInjectionFilter filter = new SqlInjectionFilter(props, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/search");
        request.addParameter("q", "1' union select password from users --");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(proceeded.get(), "Monitor mode should not block the request");
        assertEquals("SQL_INJECTION", response.getHeader("X-Sql-Guard"));
    }

    @Test
    void jwtFilterAllowsValidToken() throws ServletException, IOException {
        JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
        JwtProperties jwtProperties = new JwtProperties();
        HttpServletRequestUtils requestUtils = new HttpServletRequestUtils(jwtProperties);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());

        when(jwtUtils.validateToken(anyString(), anyString(), anyString())).thenReturn(true);
        UUID uid = UUID.randomUUID();
        when(jwtUtils.getUserIdFromToken(anyString())).thenReturn(uid);
        when(jwtUtils.getUsernameFromToken(anyString())).thenReturn("bob");
        when(jwtUtils.getPermissionsFromToken(anyString())).thenReturn(Set.of("perm:a"));
        when(jwtUtils.getRolesFromToken(anyString())).thenReturn(Set.of("role:b"));

        JwtAuthenticationFilter filter = newFilter(jwtUtils, requestUtils, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        request.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(proceeded.get(), "Valid token should proceed");
    }

    @Test
    void jwtFilterSnapshotOnInvalidToken() throws ServletException, IOException {
        JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
        JwtProperties jwtProperties = new JwtProperties();
        HttpServletRequestUtils requestUtils = new HttpServletRequestUtils(jwtProperties);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());

        when(jwtUtils.validateToken(anyString(), anyString(), anyString())).thenReturn(false);

        JwtAuthenticationFilter filter = newFilter(jwtUtils, requestUtils, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        request.addHeader("Authorization", "Bearer badtoken");
        request.addHeader("X-Request-ID", "trace-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(proceeded.get(), "Invalid token should not proceed");
        assertEquals("{\"code\":401,\"error\":\"INVALID_TOKEN\",\"message\":\"Token validation failed\",\"traceId\":\"trace-jwt\",\"path\":\"/secure\"}",
                response.getContentAsString());
    }

    @Test
    void jwtFilterBlocksInvalidToken() throws ServletException, IOException {
        JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
        JwtProperties jwtProperties = new JwtProperties();
        HttpServletRequestUtils requestUtils = new HttpServletRequestUtils(jwtProperties);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());

        when(jwtUtils.validateToken(anyString(), anyString(), anyString())).thenReturn(false);

        JwtAuthenticationFilter filter = newFilter(jwtUtils, requestUtils, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        request.addHeader("Authorization", "Bearer badtoken");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(proceeded.get(), "Invalid token should not proceed");
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_TOKEN"));
        assertNotNull(response.getHeader("X-Request-ID"));
    }

    @Test
    void stepUpFilterRequiresMfaWhenAmrMissing() throws ServletException, IOException {
        StepUpEvaluator evaluator = Mockito.mock(StepUpEvaluator.class);
        JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
        // Must be a mock: the test stubs getTokenFromRequest, and Mockito rejects stubbing a real
        // instance ("InvalidUseOfMatchers"). The JWT-filter tests keep the real implementation
        // because they drive it through an actual Authorization header.
        HttpServletRequestUtils requestUtils = Mockito.mock(HttpServletRequestUtils.class);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        StepUpProperties stepUpProperties = new StepUpProperties();

        when(evaluator.evaluate(any(), any())).thenReturn(StepUpRequirement.MFA);
        when(requestUtils.getTokenFromRequest(any())).thenReturn("tok");
        when(jwtUtils.getAmrFromToken(anyString())).thenReturn(Set.of());

        StepUpFilter filter = new StepUpFilter(evaluator, auditLogProvider(), jwtUtils, requestUtils, metrics, stepUpProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(proceeded.get(), "Should require step-up");
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentAsString().contains("STEP_UP_REQUIRED"));
        assertEquals("mfa", response.getHeader("X-StepUp-Required"));
    }

    @Test
    void stepUpFilterPassesWhenAmrSatisfied() throws ServletException, IOException {
        StepUpEvaluator evaluator = Mockito.mock(StepUpEvaluator.class);
        JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
        HttpServletRequestUtils requestUtils = Mockito.mock(HttpServletRequestUtils.class);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        StepUpProperties stepUpProperties = new StepUpProperties();

        when(evaluator.evaluate(any(), any())).thenReturn(StepUpRequirement.WEBAUTHN);
        when(requestUtils.getTokenFromRequest(any())).thenReturn("tok");
        when(jwtUtils.getAmrFromToken(anyString())).thenReturn(Set.of("webauthn"));

        StepUpFilter filter = new StepUpFilter(evaluator, auditLogProvider(), jwtUtils, requestUtils, metrics, stepUpProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(proceeded.get(), "Satisfied AMR should bypass step-up");
        assertEquals(200, response.getStatus());
    }

    /**
     * A transport failure that is not a {@code PermissionServiceException} (an unwrapped client
     * error, a discovery failure) must still fail closed: the previous behaviour leaked a raw 500
     * and, under the old circuit-breaker setting, could bypass the check entirely.
     */
    @Test
    void apiAccessControlFailsClosedOnUnexpectedFailure() throws ServletException, IOException {
        PermissionService permissionService = Mockito.mock(PermissionService.class);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        ApiAccessControlProperties apiProps = new ApiAccessControlProperties();
        apiProps.setWhitelist(List.of());

        when(permissionService.findPermissionsByUrl(any(), any()))
                .thenThrow(new RuntimeException("perm service down"));

        ApiAccessControlFilter filter = new ApiAccessControlFilter(permissionService, auditLogProvider(), metrics, apiProps);

        SecurityUser principal = SecurityUser.builder()
                .userId(UUID.randomUUID())
                .username("alice")
                .roles(Set.of())
                .permissions(Set.of())
                .build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure/data");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(proceeded.get(), "A lookup failure must not reach the handler");
        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.getStatus());
        assertTrue(response.getContentAsString().contains("PERMISSION_SERVICE_UNAVAILABLE"));
    }

    @Test
    void stepUpFilterCanBypassConfiguredPath() throws ServletException, IOException {
        StepUpEvaluator evaluator = Mockito.mock(StepUpEvaluator.class);
        ISysAuditLogService auditLogService = Mockito.mock(ISysAuditLogService.class);
        JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
        JwtProperties jwtProperties = new JwtProperties();
        HttpServletRequestUtils requestUtils = new HttpServletRequestUtils(jwtProperties);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        StepUpProperties stepUpProperties = new StepUpProperties();
        stepUpProperties.getBypassPaths().add("/transfer");

        when(evaluator.evaluate(any(), any())).thenReturn(StepUpRequirement.MFA);

        StepUpFilter filter = new StepUpFilter(evaluator, auditLogProvider(), jwtUtils, requestUtils, metrics, stepUpProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(proceeded.get(), "Bypass path should skip step-up enforcement");
        assertEquals("config", response.getHeader("X-StepUp-Bypass"));
    }

    /**
     * No audit log service in these tests: the filter must work without one (it only records the
     * denied attempt when the bean is present).
     */
    private static ObjectProvider<ISysAuditLogService> auditLogProvider() {
        return new ObjectProvider<>() {
            @Override
            public ISysAuditLogService getObject(Object... args) {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                        ISysAuditLogService.class);
            }

            @Override
            public ISysAuditLogService getIfAvailable() {
                return null;
            }

            @Override
            public ISysAuditLogService getIfUnique() {
                return null;
            }

            @Override
            public java.util.Iterator<ISysAuditLogService> iterator() {
                return java.util.Collections.emptyIterator();
            }
        };
    }

    /**
     * Builds the filter with no permission service available.
     *
     * <p>These tests drive paths that never need a permission lookup (or carry the legacy
     * {@code permissions} claim), so a missing provider must not change their outcome. The
     * fail-closed contract for a route that does need one is covered by
     * {@code JwtAuthenticationFilterPermissionResolutionTest}.
     */
    private static JwtAuthenticationFilter newFilter(JwtUtils jwtUtils,
                                                     HttpServletRequestUtils requestUtils,
                                                     SecurityMetrics metrics) {
        return newFilter(jwtUtils, requestUtils, metrics, new ApiAccessControlProperties());
    }

    private static JwtAuthenticationFilter newFilter(JwtUtils jwtUtils,
                                                     HttpServletRequestUtils requestUtils,
                                                     SecurityMetrics metrics,
                                                     ApiAccessControlProperties apiProperties) {
        return new JwtAuthenticationFilter(jwtUtils, requestUtils, metrics,
                new ObjectProvider<>() {
                    @Override
                    public com.cognora.platform.common.security.PermissionService getObject(Object... args) {
                        throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                                com.cognora.platform.common.security.PermissionService.class);
                    }

                    @Override
                    public com.cognora.platform.common.security.PermissionService getIfAvailable() {
                        return null;
                    }

                    @Override
                    public com.cognora.platform.common.security.PermissionService getIfUnique() {
                        return null;
                    }

                    @Override
                    public java.util.Iterator<com.cognora.platform.common.security.PermissionService> iterator() {
                        return java.util.Collections.emptyIterator();
                    }
                },
                apiProperties);
    }
}
