package com.cognora.platform.auth.service;

import com.cognora.platform.auth.service.Impl.SysAuthServiceImpl;
import com.cognora.platform.auth.service.support.SessionLocationResolver;
import com.cognora.platform.common.exception.UnauthorizedException;
import com.cognora.platform.common.log.service.ISysAuditLogService;
import com.cognora.platform.common.metrics.BusinessMetrics;
import com.cognora.platform.common.redis.script.LuaScriptRegistry;
import com.cognora.platform.common.rest.client.SysUserServiceClient;
import com.cognora.platform.common.security.properties.JwtProperties;
import com.cognora.platform.common.security.properties.SecurityProperties;
import com.cognora.platform.common.security.util.JwtUtils;
import com.cognora.platform.common.security.util.TotpUtils;
import com.cognora.platform.system.api.UserDubboService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话端点：userId 只能来自访问令牌，设备标记从令牌 deviceId 推导。
 */
@ExtendWith(MockitoExtension.class)
class SysAuthServiceImplSessionTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtUtils jwtUtils;
    @Mock private SysUserServiceClient userServiceClient;
    @Mock private ISysAuditLogService auditLogService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private SecurityProperties securityProperties;
    @Mock private JwtProperties jwtProperties;
    @Mock private BusinessMetrics businessMetrics;
    @Mock private TotpUtils totpUtils;
    @Mock private UserDubboService userDubboService;
    @Mock private TenantClaimResolver tenantClaimResolver;
    @Mock private LuaScriptRegistry luaScriptRegistry;

    @InjectMocks private SysAuthServiceImpl service;

    private static final String TOKEN = "access-token";

    @Test
    void listSessionsResolvesUserIdFromTokenAndMarksCurrentDevice() {
        UUID userId = UUID.randomUUID();
        when(jwtUtils.getUserIdFromToken(TOKEN)).thenReturn(userId);
        when(jwtUtils.getDeviceIdFromToken(TOKEN)).thenReturn("device-current");
        String chromeWindows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        String safariIphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
        when(jwtUtils.listActiveSessions(userId)).thenReturn(List.of(
                new JwtUtils.TokenSession("device-current", "10.0.0.1",
                        Instant.parse("2026-11-06T08:00:00Z"), Instant.parse("2026-11-06T09:00:00Z"),
                        chromeWindows, "Asia/Shanghai"),
                new JwtUtils.TokenSession("device-other", "203.0.113.10",
                        Instant.parse("2026-11-06T07:00:00Z"), Instant.parse("2026-11-06T08:00:00Z"),
                        safariIphone, "Asia/Shanghai")));

        var sessions = service.listSessions(TOKEN);

        assertEquals(2, sessions.size());
        assertEquals("device-current", sessions.get(0).getSessionId());
        assertEquals("10.0.0.1", sessions.get(0).getIp());
        assertTrue(sessions.get(0).isCurrent());
        assertEquals(Instant.parse("2026-11-06T08:00:00Z"), sessions.get(0).getLastActiveAt());
        assertEquals("Chrome · Windows", sessions.get(0).getDeviceName());
        assertEquals("web", sessions.get(0).getDeviceType());
        assertEquals("本地网络", sessions.get(0).getLocation());
        assertEquals(chromeWindows, sessions.get(0).getUserAgent());
        assertFalse(sessions.get(1).isCurrent());
        assertEquals("Safari · iOS", sessions.get(1).getDeviceName());
        assertEquals("mobile", sessions.get(1).getDeviceType());
        assertEquals(SessionLocationResolver.resolve("203.0.113.10", "Asia/Shanghai"),
                sessions.get(1).getLocation());
        assertNotNull(sessions.get(1).getUserAgent());
        verify(jwtUtils).listActiveSessions(userId);
    }

    @Test
    void listSessionsRejectsMissingToken() {
        assertThrows(UnauthorizedException.class, () -> service.listSessions(" "));
        verifyNoInteractions(jwtUtils);
    }

    @Test
    void logoutOtherSessionsUsesTokenUserAndCurrentDevice() {
        UUID userId = UUID.randomUUID();
        when(jwtUtils.getUserIdFromToken(TOKEN)).thenReturn(userId);
        when(jwtUtils.getDeviceIdFromToken(TOKEN)).thenReturn("device-current");
        when(jwtUtils.revokeOtherDeviceTokens(eq(userId), eq("device-current"), anyString())).thenReturn(2);

        int revoked = service.logoutOtherSessions(TOKEN);

        assertEquals(2, revoked);
        verify(jwtUtils).revokeOtherDeviceTokens(eq(userId), eq("device-current"), anyString());
        verify(auditLogService).recordLogout(eq(userId), anyString());
    }

    @Test
    void logoutOtherSessionsRejectsTokenWithoutDeviceBinding() {
        UUID userId = UUID.randomUUID();
        when(jwtUtils.getUserIdFromToken(TOKEN)).thenReturn(userId);

        assertThrows(UnauthorizedException.class, () -> service.logoutOtherSessions(TOKEN));
        verify(jwtUtils, never()).revokeOtherDeviceTokens(eq(userId), isNull(), anyString());
    }
}
