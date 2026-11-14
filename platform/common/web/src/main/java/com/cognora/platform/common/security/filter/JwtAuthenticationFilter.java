package com.cognora.platform.common.security.filter;

import com.cognora.platform.common.security.PermissionService;
import com.cognora.platform.common.security.config.ApiAccessControlProperties;
import com.cognora.platform.common.security.metrics.SecurityMetrics;
import com.cognora.platform.common.security.util.AuthTokenContext;
import com.cognora.platform.common.security.util.HttpServletRequestUtils;
import com.cognora.platform.common.security.util.IpUtils;
import com.cognora.platform.common.security.util.JwtUtils;
import com.cognora.platform.common.security.util.RequestPathMatcher;
import com.cognora.platform.common.security.util.SecurityErrorResponseWriter;
import com.cognora.platform.common.web.domain.SecurityUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Jwt 杩囨护锟?
 *
 * @author Deng
 * createData 2025/10/11 13:49
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
@NullMarked
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Request attribute set once this filter has established the caller's authoritative
     * permission set. {@link ApiAccessControlFilter} reuses it instead of resolving the same
     * user's permissions a second time in the same request.
     */
    public static final String PERMISSIONS_RESOLVED_ATTRIBUTE =
            "cognora.security.permissions-resolved";

    private final JwtUtils jwtUtils;
    private final HttpServletRequestUtils httpServletRequestUtils;
    private final SecurityMetrics securityMetrics;
    /**
     * Resolves the caller's permissions server-side. Access tokens no longer embed the
     * permission list (a tenant administrator's token was ~26 KB, larger than a browser's
     * per-cookie limit), so authorities must come from the permission service. Using
     * {@code ObjectProvider} keeps services that omit the auto-configuration booting.
     */
    private final ObjectProvider<PermissionService> permissionServiceProvider;

    private final ApiAccessControlProperties apiAccessControlProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, jakarta.servlet.ServletException {
        try {
            // 鑾峰彇 Token
            String token = httpServletRequestUtils.getTokenFromRequest(request);

            if (StringUtils.hasText(token)) {
                // 鑾峰彇褰撳墠璇锋眰淇℃伅
                String currentIp = IpUtils.getClientIp(request);
                String currentDeviceId = httpServletRequestUtils.getDeviceId(request);

                // 楠岃瘉 Token
                if (jwtUtils.validateToken(token, currentIp, currentDeviceId)) {
                    // Token 必须绑定租户，且与请求头租户一致；旧 Token（无租户声明）统一 401
                    JwtUtils.TenantBinding tenantBinding =
                            jwtUtils.validateTenantBinding(token, resolveTenantId(request));
                    if (tenantBinding == JwtUtils.TenantBinding.CLAIM_MISSING) {
                        securityMetrics.increment("security.jwt.tenant_missing");
                        SecurityErrorResponseWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                "invalid_token_tenant_missing", "Token is missing tenant binding, please sign in again");
                        return;
                    }
                    if (tenantBinding == JwtUtils.TenantBinding.MISMATCH) {
                        securityMetrics.increment("security.jwt.tenant_mismatch");
                        SecurityErrorResponseWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                "invalid_token_tenant_mismatch", "Token tenant does not match request tenant");
                        return;
                    }
                    AuthTokenContext.set(token, currentDeviceId);
                    // 鎻愬彇鐢ㄦ埛淇℃伅锛堝緱鐩婁簬ThreadLocal缂撳瓨锛岃繖4娆¤皟鐢ㄥ彧浼氳В鏋怲oken涓€娆★級
                    UUID userId = jwtUtils.getUserIdFromToken(token);
                    String username = jwtUtils.getUsernameFromToken(token);
                    Set<String> permissions = resolvePermissions(request, token, userId, response);
                    if (permissions == null) {
                        // The permission service failed on a route that has to resolve permissions
                        // per request. Answering now keeps the filter chain from turning a lookup
                        // outage into a wave of 403s (or, worse, into role-only access).
                        return;
                    }
                    Set<String> roles = jwtUtils.getRolesFromToken(token);
                    UUID tenantId = jwtUtils.getTenantIdFromToken(token);
                    String tenantCode = jwtUtils.getTenantCodeFromToken(token);

                    // 鏋勫缓鏉冮檺鍒楄〃
                    Set<SimpleGrantedAuthority> authorities = permissions.stream()
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toSet());

                    authorities.addAll(roles.stream()
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toSet()));

                    // 鍒涘缓璁よ瘉瀵硅薄
                    SecurityUser userDetails = SecurityUser.builder()
                            .userId(userId)
                            .username(username)
                            .permissions(permissions)
                            .roles(roles)
                            .tenantId(tenantId)
                            .tenantCode(tenantCode)
                            .build();

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                    userDetails, null, authorities);

                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // 璁剧疆锟絊ecurity涓婁笅锟?
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("User authenticated: traceId={}, userId={}, username={}",
                            (request.getHeader("X-Request-ID") + "").replace("\r", "_")
                                    .replace("\n", "_"), (userId + "").replace("\r", "_")
                                    .replace("\n", "_"), (username + "").replace("\r", "_")
                                    .replace("\n", "_"));
                } else {
                    securityMetrics.increment("security.jwt.invalid");
                    SecurityErrorResponseWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                            "INVALID_TOKEN", "Token validation failed");
                    return;
                }
            }

        } catch (Exception e) {
            securityMetrics.increment("security.jwt.errors");
            log.error("Cannot set user authentication traceId={}", (request.getHeader("X-Request-ID") + "").replace("\r", "_").replace("\n", "_"), e);
            SecurityErrorResponseWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_ERROR",
                    "Authentication error");
            return;
        } finally {
            // 娓呯悊ThreadLocal缂撳瓨锛岄槻姝㈠唴瀛樻硠锟?
            JwtUtils.clearTokenCache();
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthTokenContext.clear();
        }
    }

    /**
     * Resolves the authorities for this request.
     *
     * <p><strong>Contract.</strong> When the permission service cannot answer, this filter does
     * not guess. The request is answered immediately with {@code 503
     * PERMISSION_SERVICE_UNAVAILABLE} - the same status and error code
     * {@code ApiAccessControlFilter} uses - unless the route can never need a permission, in
     * which case the request continues with only the roles carried by the token. The distinction
     * is what makes the behaviour predictable:
     *
     * <ul>
     *   <li>A route that always needs a permission ({@code ApiAccessControlFilter} would look one
     *       up) fails closed. Continuing with roles only would either hand out a 403 (an outage
     *       looks like a permissions bug) or, for a role-gated handler, grant access on evidence
     *       the caller never presented.</li>
     *   <li>A route that never needs one (exempt / whitelisted paths such as logout, self-service
     *       profile updates, session listing) keeps working through a permission-service outage.
     *       These are the requests that let an operator sign out or rotate credentials while
     *       system is down.</li>
     * </ul>
     *
     * <p>Access tokens minted before the shortening still carry a {@code permissions} claim, so it
     * is used when present and those sessions keep working unchanged until they expire.
     *
     * @return the resolved permissions, or {@code null} when the response was already written
     */
    private Set<String> resolvePermissions(HttpServletRequest request, String token, UUID userId,
                                           HttpServletResponse response) throws IOException {
        Set<String> fromToken = jwtUtils.getPermissionsFromToken(token);
        if (!fromToken.isEmpty()) {
            // Legacy token: the claim is used as-is, but it is NOT marked resolved, so
            // ApiAccessControlFilter still performs its own authoritative lookup. A permission
            // revoked after the token was minted must not survive on a token that outlives it.
            return fromToken;
        }
        if (permissionLookupNotRequired(request)) {
            // Documented outcome: no permission can be required on this route, so an empty set is
            // the truthful answer and the request still authenticates. Do not call the service.
            return Set.of();
        }
        PermissionService permissionService = permissionServiceProvider.getIfAvailable();
        if (permissionService == null) {
            // No PermissionService bean at all. Otherwise every request would be rejected; that is
            // the fail-closed answer, and it is reported with the unavailable code.
            writePermissionServiceUnavailable(request, response, "no PermissionService is available");
            return null;
        }
        try {
            Set<String> resolved = permissionService.findAllPermissionsByUserId(userId);
            securityMetrics.increment("security.permissions.resolved");
            Set<String> permissions = resolved != null ? resolved : Set.of();
            request.setAttribute(PERMISSIONS_RESOLVED_ATTRIBUTE, Boolean.TRUE);
            return permissions;
        } catch (RuntimeException ex) {
            // PermissionServiceException is a RuntimeException; catching RuntimeException as well
            // covers a client that fails before it can wrap the failure.
            securityMetrics.increment("security.permissions.resolve_failed");
            writePermissionServiceUnavailable(request, response, ex.getMessage());
            return null;
        }
    }

    /**
     * Whether a permission lookup can be skipped for this request.
     *
     * <p>Mirrors the branches of {@link ApiAccessControlFilter} that return before it asks for a
     * permission: the filter being disabled, a whitelisted path, an exempt path, or a request
     * whose service signature was already verified.
     */
    private boolean permissionLookupNotRequired(HttpServletRequest request) {
        if (!apiAccessControlProperties.isEnabled()) {
            return true;
        }
        String uri = request.getRequestURI();
        if (RequestPathMatcher.matches(uri, apiAccessControlProperties.getWhitelist())) {
            return true;
        }
        if (RequestPathMatcher.matches(uri, apiAccessControlProperties.getExemptPaths())
                && !RequestPathMatcher.matches(uri, apiAccessControlProperties.getExemptPathExclusions())) {
            return true;
        }
        return Boolean.TRUE.equals(request.getAttribute(
                InternalEndpointSignatureFilter.SIGNATURE_VERIFIED_ATTRIBUTE));
    }

    private void writePermissionServiceUnavailable(HttpServletRequest request, HttpServletResponse response,
                                                   String reason) throws IOException {
        securityMetrics.increment("security.permissions.unavailable");
        log.error("Cannot resolve permissions for traceId={}, uri={}: {}. Answering 503 instead of "
                        + "continuing with role authorities only.",
                (request.getHeader("X-Request-ID") + "").replace("\r", "_").replace("\n", "_"),
                (request.getRequestURI() + "").replace("\r", "_").replace("\n", "_"),
                (reason + "").replace("\r", "_").replace("\n", "_"));
        SecurityErrorResponseWriter.write(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "PERMISSION_SERVICE_UNAVAILABLE",
                "Permission service is temporarily unavailable; access denied as a safety measure");
    }

    /**
     * 与 TenantFilter 相同的租户来源顺序：X-Tenant-Id → Tenant-Id → tenantId 查询参数。
     */
    private static String resolveTenantId(HttpServletRequest request) {
        String tenantId = request.getHeader("X-Tenant-Id");
        if (!StringUtils.hasText(tenantId)) {
            tenantId = request.getHeader("Tenant-Id");
        }
        if (!StringUtils.hasText(tenantId)) {
            tenantId = request.getParameter("tenantId");
        }
        return tenantId;
    }
}
