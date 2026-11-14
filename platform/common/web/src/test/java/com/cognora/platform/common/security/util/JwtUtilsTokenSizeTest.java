package com.cognora.platform.common.security.util;

import com.cognora.platform.common.security.properties.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the access-token size contract.
 *
 * <p>A tenant administrator holds several hundred permission codes. When those were serialised
 * into the token, the token grew to roughly 26 KB - far past the ~4 KB a browser accepts in a
 * single cookie. The browser then silently dropped the cookie, the session never survived a
 * navigation, and the SPA was bounced straight back to the login page after a "successful" login.
 *
 * <p>These tests pin the fix: permissions must stay out of the token, and the token must stay
 * comfortably under the cookie ceiling even for an administrator with hundreds of permissions.
 */
@DisplayName("JWT access token size contract")
class JwtUtilsTokenSizeTest {

    /** Practical per-cookie limit in browsers; above this a cookie is silently discarded. */
    private static final int BROWSER_COOKIE_LIMIT = 4096;

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        // HS512 requires a key of at least 64 bytes.
        properties.setSecret("unit-test-secret-key-0123456789abcdef0123456789abcdef0123456789abcdef");
        properties.setExpiration(7200000L);
        properties.setRefreshExpiration(604800000L);
        properties.setIssuer("unit-test-issuer");

        jwtUtils = new JwtUtils(
                properties,
                mock(RedisTemplate.class),
                mock(StringRedisTemplate.class));
        // Spring invokes this through @PostConstruct; outside a container it must be called so
        // the HS512 signing key is derived from the configured secret.
        jwtUtils.init();
    }

    private String issueToken(Set<String> roles, Set<String> permissions,
                              UUID userId, UUID tenantId, String audience) {
        return jwtUtils.generateAccessToken(
                userId, "admin", roles, permissions,
                "device-1", "127.0.0.1", java.util.List.of("pwd"),
                tenantId, "default", audience);
    }

    @Test
    @DisplayName("Access token does not embed the permission list")
    void accessTokenOmitsPermissions() {
        Set<String> permissions = IntStream.range(0, 600)
                .mapToObj(i -> "order:order:permission-" + i)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String token = issueToken(Set.of("ROLE_TENANT_ADMIN"), permissions,
                UUID.randomUUID(), UUID.randomUUID(), "cognora-admin");

        assertThat(jwtUtils.getPermissionsFromToken(token)).isEmpty();
        assertThat(token).doesNotContain("permissions");
    }

    @Test
    @DisplayName("Access token stays small enough to live in a browser cookie")
    void accessTokenFitsInACookie() {
        Set<String> permissions = IntStream.range(0, 600)
                .mapToObj(i -> "order:order:permission-" + i)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> roles = Set.of("ROLE_TENANT_ADMIN", "ROLE_SUPER_ADMIN");

        String token = issueToken(roles, permissions,
                UUID.randomUUID(), UUID.randomUUID(), "cognora-admin");

        assertThat(token.length()).isLessThan(BROWSER_COOKIE_LIMIT);
    }

    @Test
    @DisplayName("Access token still carries the claims the gateway and filters require")
    void accessTokenKeepsRequiredClaims() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = issueToken(Set.of("ROLE_TENANT_ADMIN"), Set.of(),
                userId, tenantId, "cognora-admin");

        // The audience drives the gateway's AudienceAuthorizationManager; the tenant claim
        // drives TenantFilter, and roles still authorise role-gated endpoints.
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).contains("cognora-admin");
        assertThat(jwtUtils.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(jwtUtils.getTenantIdFromToken(token)).isEqualTo(tenantId);
        assertThat(jwtUtils.getRolesFromToken(token)).containsExactly("ROLE_TENANT_ADMIN");
    }
}
