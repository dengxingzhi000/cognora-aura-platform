package com.cognora.platform.common.security.filter;

import com.cognora.platform.common.security.PermissionService;
import com.cognora.platform.common.security.PermissionService.PermissionServiceException;
import com.cognora.platform.common.security.config.ApiAccessControlProperties;
import com.cognora.platform.common.security.metrics.SecurityMetrics;
import com.cognora.platform.common.security.properties.JwtProperties;
import com.cognora.platform.common.security.util.HttpServletRequestUtils;
import com.cognora.platform.common.security.util.JwtUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the permission-resolution contract of the short-token change.
 *
 * <p>Access tokens no longer carry the permission list, so every request that needs a permission
 * resolves it from the permission service. The contract under test:
 *
 * <ul>
 *   <li>a route that needs a permission fails closed with {@code 503
 *       PERMISSION_SERVICE_UNAVAILABLE} when the lookup fails - it never continues with role
 *       authorities only;</li>
 *   <li>a route that can never need a permission (exempt/whitelisted, e.g. logout) keeps working
 *       through the outage, so an operator can still sign out;</li>
 *   <li>a successful lookup is marked on the request so {@link ApiAccessControlFilter} does not
 *       resolve the same user a second time;</li>
 *   <li>a legacy token that still embeds permissions is honoured, but is not marked resolved, so
 *       the endpoint-level check still performs its own authoritative lookup.</li>
 * </ul>
 */
@DisplayName("Permission resolution: fail closed, once per request")
class JwtAuthenticationFilterPermissionResolutionTest {

    private static final String PROTECTED_ROUTE = "/secure/data";
    private static final String EXEMPT_ROUTE = "/api/auth/logout";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final HttpServletRequestUtils requestUtils =
            new HttpServletRequestUtils(new JwtProperties());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Lookup failure on a permission-gated route answers 503 and stops the chain")
    void lookupFailureOnProtectedRouteFailsClosed() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.findAllPermissionsByUserId(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new PermissionServiceException("permission service unavailable"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);

        filterWith(permissionService).doFilter(authenticatedRequest(PROTECTED_ROUTE),
                response, chain(proceeded));

        assertFalse(proceeded.get(), "A failed permission lookup must not reach the handler");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("PERMISSION_SERVICE_UNAVAILABLE"));
        assertEquals(1.0, meterRegistry.counter("security.permissions.unavailable").count());
    }

    @Test
    @DisplayName("Lookup failure on an exempt route still authenticates (logout survives)")
    void lookupFailureOnExemptRouteStillProceeds() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);

        MockHttpServletRequest request = authenticatedRequest(EXEMPT_ROUTE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);

        filterWith(permissionService).doFilter(request, response, chain(proceeded));

        assertTrue(proceeded.get(), "An exempt route must not depend on the permission service");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication != null && authentication.isAuthenticated());
        assertTrue(authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_OPERATOR".equals(authority.getAuthority())),
                "The roles carried by the token must still be granted");
        // The service is not consulted at all for a route that cannot need a permission.
        verify(permissionService, never())
                .findAllPermissionsByUserId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Successful lookup marks the request so the endpoint check reuses it")
    void successfulLookupIsMarkedForReuse() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.findAllPermissionsByUserId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Set.of("order:order:view"));

        MockHttpServletRequest request = authenticatedRequest(PROTECTED_ROUTE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);

        filterWith(permissionService).doFilter(request, response, chain(proceeded));

        assertTrue(proceeded.get());
        assertEquals(Boolean.TRUE, request.getAttribute(
                JwtAuthenticationFilter.PERMISSIONS_RESOLVED_ATTRIBUTE));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> "order:order:view".equals(authority.getAuthority())));
    }

    @Test
    @DisplayName("ApiAccessControlFilter reuses the resolved set instead of a second lookup")
    void accessControlReusesResolvedPermissions() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.findPermissionsByUrl(anyString(), anyString()))
                .thenReturn(List.of("order:order:view"));
        when(permissionService.findAllPermissionsByUserId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Set.of("order:order:view"));

        MockHttpServletRequest request = authenticatedRequest(PROTECTED_ROUTE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);

        JwtAuthenticationFilter jwtFilter = filterWith(permissionService);
        jwtFilter.doFilter(request, response, (req, res) ->
                new ApiAccessControlFilter(permissionService,
                        new ObjectProvider<>() {
                            @Override
                            public com.cognora.platform.common.log.service.ISysAuditLogService getObject(Object... args) {
                                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                                        com.cognora.platform.common.log.service.ISysAuditLogService.class);
                            }

                            @Override
                            public com.cognora.platform.common.log.service.ISysAuditLogService getIfAvailable() {
                                return null;
                            }

                            @Override
                            public com.cognora.platform.common.log.service.ISysAuditLogService getIfUnique() {
                                return null;
                            }

                            @Override
                            public Iterator<com.cognora.platform.common.log.service.ISysAuditLogService> iterator() {
                                return Collections.emptyIterator();
                            }
                        },
                        new SecurityMetrics(meterRegistry),
                        apiProperties())
                        .doFilter(req, res, chain(proceeded)));

        assertTrue(proceeded.get());
        // Exactly one user-permission lookup for the whole request: the one the JWT filter made.
        verify(permissionService, org.mockito.Mockito.times(1))
                .findAllPermissionsByUserId(org.mockito.ArgumentMatchers.any());
        assertEquals(1.0, meterRegistry.counter("security.access.permissions.reused").count());
    }

    @Test
    @DisplayName("Legacy token permissions are honoured but not marked resolved")
    void legacyTokenPermissionsAreNotMarkedResolved() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);

        MockHttpServletRequest request = authenticatedRequest(PROTECTED_ROUTE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);

        filterWith(permissionService, Set.of("legacy:permission")).doFilter(request, response,
                chain(proceeded));

        assertTrue(proceeded.get());
        assertNull(request.getAttribute(JwtAuthenticationFilter.PERMISSIONS_RESOLVED_ATTRIBUTE),
                "A permission claim minted into the token must not skip the authoritative lookup");
        verify(permissionService, never())
                .findAllPermissionsByUserId(org.mockito.ArgumentMatchers.any());
    }

    private JwtAuthenticationFilter filterWith(PermissionService permissionService) {
        return filterWith(permissionService, Set.of());
    }

    private JwtAuthenticationFilter filterWith(PermissionService permissionService,
                                               Set<String> tokenPermissions) {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        UUID userId = UUID.randomUUID();
        when(jwtUtils.validateToken(anyString(), anyString(), anyString())).thenReturn(true);
        when(jwtUtils.getUserIdFromToken(anyString())).thenReturn(userId);
        when(jwtUtils.getUsernameFromToken(anyString())).thenReturn("operator");
        when(jwtUtils.getRolesFromToken(anyString())).thenReturn(Set.of("ROLE_OPERATOR"));
        when(jwtUtils.getPermissionsFromToken(anyString())).thenReturn(tokenPermissions);
        when(jwtUtils.getTenantIdFromToken(anyString())).thenReturn(UUID.randomUUID());
        when(jwtUtils.validateTenantBinding(anyString(), anyString()))
                .thenReturn(JwtUtils.TenantBinding.VALID);

        return new JwtAuthenticationFilter(jwtUtils, requestUtils,
                new SecurityMetrics(meterRegistry),
                new ObjectProvider<>() {
                    @Override
                    public PermissionService getObject(Object... args) {
                        return permissionService;
                    }

                    @Override
                    public PermissionService getIfAvailable() {
                        return permissionService;
                    }

                    @Override
                    public PermissionService getIfUnique() {
                        return permissionService;
                    }

                    @Override
                    public Iterator<PermissionService> iterator() {
                        return List.of(permissionService).iterator();
                    }
                },
                apiProperties());
    }

    private static ApiAccessControlProperties apiProperties() {
        return new ApiAccessControlProperties();
    }

    private static MockHttpServletRequest authenticatedRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Authorization", "Bearer token");
        request.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        request.addHeader("X-Request-ID", "trace-" + uri.hashCode());
        return request;
    }

    private static FilterChain chain(AtomicBoolean proceeded) {
        return (request, response) -> proceeded.set(true);
    }
}
