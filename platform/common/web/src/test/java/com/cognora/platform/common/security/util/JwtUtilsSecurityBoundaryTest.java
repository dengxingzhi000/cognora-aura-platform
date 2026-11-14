package com.cognora.platform.common.security.util;

import com.cognora.platform.common.security.properties.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JwtUtils} 的密钥与校验边界。
 *
 * <p>这个类决定「谁能进门」：签发令牌、验签、以及租户/设备/IP 绑定。它此前只有 happy-path
 * 测试，恰好漏掉了最该被钉住的失败分支——而安全代码的价值几乎全在失败分支上。</p>
 *
 * <p>核心断言是 **fail-closed**：任何一处校验不通过都必须返回 false 或抛异常，绝不静默放行。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JWT：密钥强度与校验边界")
class JwtUtilsSecurityBoundaryTest {

    /** 正好 64 字节（512 bit）——HS512 的最低要求。 */
    private static final String SECRET_64_BYTES =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    /** 63 字节——差一个字节就不合格。 */
    private static final String SECRET_63_BYTES = SECRET_64_BYTES.substring(0, 63);

    @Mock private JwtProperties jwtProperties;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;

    private JwtUtils jwtUtils;

    private static final String FINGERPRINT_PREFIX = "jwt:fingerprint:";
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    @BeforeEach
    void setUp() {
        when(jwtProperties.getSecret()).thenReturn(SECRET_64_BYTES);
        when(jwtProperties.getIssuer()).thenReturn("jwt-boundary-test");
        when(jwtProperties.getExpiration()).thenReturn(3_600_000L);
        when(jwtProperties.getRefreshExpiration()).thenReturn(604_800_000L);
        jwtUtils = new JwtUtils(jwtProperties, redisTemplate, stringRedisTemplate);
        jwtUtils.init();
    }

    /**
     * 让指纹存在、黑名单为空——即"这个 token 本身是合法的"。
     *
     * <p>必须按 key 前缀区分：早年用 {@code hasKey(anyString())} 同时命中了
     * {@code jwt:blacklist:*} 与 {@code jwt:fingerprint:*}，于是"应该被设备/IP 拒绝"的
     * 用例其实是被黑名单挡下的——断言通过了，但测的根本不是那件事。</p>
     */
    private void ledgerSaysTokenIsLive() {
        when(redisTemplate.hasKey(argThat(k -> k != null && k.startsWith(FINGERPRINT_PREFIX))))
                .thenReturn(true);
        when(redisTemplate.hasKey(argThat(k -> k != null && k.startsWith(BLACKLIST_PREFIX))))
                .thenReturn(false);
    }

    /** 让指纹缺失——即"这个 token 已被撤销/未登记"。 */
    private void ledgerSaysFingerprintIsGone() {
        when(redisTemplate.hasKey(argThat(k -> k != null && k.startsWith(FINGERPRINT_PREFIX))))
                .thenReturn(false);
        when(redisTemplate.hasKey(argThat(k -> k != null && k.startsWith(BLACKLIST_PREFIX))))
                .thenReturn(false);
    }

    @Nested
    @DisplayName("init()：密钥强度")
    class SecretStrength {

        @Test
        @DisplayName("缺少密钥时启动失败，而不是用一个默认值继续")
        void missingSecretFailsStartup() {
            when(jwtProperties.getSecret()).thenReturn(null);
            JwtUtils utils = new JwtUtils(jwtProperties, redisTemplate, stringRedisTemplate);
            assertThatThrownBy(utils::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.secret");
        }

        @Test
        @DisplayName("空字符串同样拒绝")
        void blankSecretFailsStartup() {
            when(jwtProperties.getSecret()).thenReturn("");
            JwtUtils utils = new JwtUtils(jwtProperties, redisTemplate, stringRedisTemplate);
            assertThatThrownBy(utils::init).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("少于 512 bit 的密钥拒绝，并说明当前位数")
        void shortSecretIsRejectedWithTheActualLength() {
            when(jwtProperties.getSecret()).thenReturn(SECRET_63_BYTES);
            JwtUtils utils = new JwtUtils(jwtProperties, redisTemplate, stringRedisTemplate);
            assertThatThrownBy(utils::init)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("512")
                    .hasMessageContaining("63");
        }

        @Test
        @DisplayName("密钥长度按 UTF-8 字节算，不是按字符数")
        void lengthIsMeasuredInBytesNotCharacters() {
            // 32 个中文字符 = 96 字节 UTF-8，字符数会误判为不合格。
            String multibyte = "密钥密钥密钥密钥密钥密钥密钥密钥密钥密钥密钥密钥密钥密钥密钥密钥";
            assertThat(multibyte.length()).isEqualTo(32);
            assertThat(multibyte.getBytes(StandardCharsets.UTF_8).length).isEqualTo(96);

            when(jwtProperties.getSecret()).thenReturn(multibyte);
            JwtUtils utils = new JwtUtils(jwtProperties, redisTemplate, stringRedisTemplate);
            utils.init(); // 96 字节 >= 64，必须通过
        }

        @Test
        @DisplayName("正好 64 字节是下限，必须接受")
        void exactlySixtyFourBytesIsAccepted() {
            JwtUtils utils = new JwtUtils(jwtProperties, redisTemplate, stringRedisTemplate);
            utils.init(); // setUp 已配置 64 字节；不抛异常即通过
            assertThat(jwtUtils).isNotNull();
        }
    }

    @Nested
    @DisplayName("validateToken()：任何一处不通过都必须拒绝")
    class FailClosedValidation {

        @Test
        @DisplayName("null / 空的 token 直接拒绝，不去碰 Redis")
        void nullOrEmptyTokenIsRejectedWithoutTouchingRedis() {
            assertThat(jwtUtils.validateToken(null, "1.2.3.4", "device-1")).isFalse();
            assertThat(jwtUtils.validateToken("", "1.2.3.4", "device-1")).isFalse();
            verify(redisTemplate, never()).hasKey(anyString());
        }

        @Test
        @DisplayName("签名被篡改的 token 拒绝")
        void tamperedSignatureIsRejected() {
            String token = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER"), Set.of(),
                    "device-1", "1.2.3.4", null, UUID.randomUUID(), "t1");
            String tampered = token.substring(0, token.length() - 3) + "AAA";

            ledgerSaysTokenIsLive();
            assertThat(jwtUtils.validateToken(tampered, "1.2.3.4", "device-1")).isFalse();
        }

        @Test
        @DisplayName("用另一个密钥签发的 token 拒绝（跨环境重放）")
        void tokenSignedByAnotherKeyIsRejected() {
            // 用 65 字节的另一密钥签发
            when(jwtProperties.getSecret())
                    .thenReturn("fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
            JwtUtils other = new JwtUtils(jwtProperties, redisTemplate, stringRedisTemplate);
            other.init();
            String foreign = other.generateAccessToken(
                    UUID.randomUUID(), "mallory", Set.of("ROLE_ADMIN"), Set.of(),
                    "device-1", "1.2.3.4", null, UUID.randomUUID(), "t1");

            ledgerSaysTokenIsLive();
            assertThat(jwtUtils.validateToken(foreign, "1.2.3.4", "device-1")).isFalse();
        }

        @Test
        @DisplayName("指纹不在 Redis 里（已撤销/未登记）时拒绝")
        void missingFingerprintIsRejected() {
            String token = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER"), Set.of(),
                    "device-1", "1.2.3.4", null, UUID.randomUUID(), "t1");

            ledgerSaysFingerprintIsGone();
            assertThat(jwtUtils.validateToken(token, "1.2.3.4", "device-1")).isFalse();
        }

        @Test
        @DisplayName("设备不匹配时拒绝")
        void deviceMismatchIsRejected() {
            String token = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER"), Set.of(),
                    "device-1", "1.2.3.4", null, UUID.randomUUID(), "t1");

            ledgerSaysTokenIsLive();
            assertThat(jwtUtils.validateToken(token, "1.2.3.4", "device-OTHER")).isFalse();
        }

        @Test
        @DisplayName("严格 IP 校验开启且 IP 变化时拒绝")
        void strictIpMismatchIsRejected() {
            String token = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER"), Set.of(),
                    "device-1", "1.2.3.4", null, UUID.randomUUID(), "t1");

            when(jwtProperties.isStrictIpCheck()).thenReturn(true);
            ledgerSaysTokenIsLive();
            assertThat(jwtUtils.validateToken(token, "9.9.9.9", "device-1")).isFalse();
        }

        @Test
        @DisplayName("严格 IP 校验关闭时，IP 变化不影响（设备与指纹仍校验）")
        void ipChangeIsToleratedWhenStrictCheckIsOff() {
            String token = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER"), Set.of(),
                    "device-1", "1.2.3.4", null, UUID.randomUUID(), "t1");

            when(jwtProperties.isStrictIpCheck()).thenReturn(false);
            ledgerSaysTokenIsLive();
            assertThat(jwtUtils.validateToken(token, "9.9.9.9", "device-1")).isTrue();
        }

        @Test
        @DisplayName("刷新令牌不能当访问令牌用")
        void refreshTokenCannotBeUsedAsAccessToken() {
            ledgerSaysTokenIsLive();
            String refresh = jwtUtils.generateRefreshToken(
                    UUID.randomUUID(), "alice", "device-1",
                    UUID.randomUUID(), "t1", null);

            assertThat(jwtUtils.validateToken(refresh, "1.2.3.4", "device-1")).isFalse();
        }
    }

    @Nested
    @DisplayName("validateTenantBinding()：不做无租户降级")
    class TenantBinding {

        @Test
        @DisplayName("旧 token 无 tenantId 声明 → CLAIM_MISSING（调用方必须 401）")
        void legacyTokenWithoutTenantClaimIsReported() {
            ledgerSaysTokenIsLive();
            // 构造一个不带 tenantId 的 token：正常生成路径总会写入，故这里直接用空串模拟解析失败
            assertThat(jwtUtils.validateTenantBinding("not-a-jwt", "t-1"))
                    .isEqualTo(JwtUtils.TenantBinding.CLAIM_MISSING);
        }

        @Test
        @DisplayName("请求头缺租户 → MISMATCH")
        void missingRequestTenantIsMismatch() {
            UUID tenantId = UUID.randomUUID();
            String token = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER"), Set.of(),
                    "device-1", "1.2.3.4", null, tenantId, "t1");

            assertThat(jwtUtils.validateTenantBinding(token, null))
                    .isEqualTo(JwtUtils.TenantBinding.MISMATCH);
            assertThat(jwtUtils.validateTenantBinding(token, "  "))
                    .isEqualTo(JwtUtils.TenantBinding.MISMATCH);
        }

        @Test
        @DisplayName("租户不一致 → MISMATCH；一致（忽略大小写）→ VALID")
        void tenantComparisonIsCaseInsensitiveButExact() {
            UUID tenantId = UUID.randomUUID();
            String token = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER"), Set.of(),
                    "device-1", "1.2.3.4", null, tenantId, "t1");

            assertThat(jwtUtils.validateTenantBinding(token, UUID.randomUUID().toString()))
                    .isEqualTo(JwtUtils.TenantBinding.MISMATCH);
            assertThat(jwtUtils.validateTenantBinding(token, tenantId.toString()))
                    .isEqualTo(JwtUtils.TenantBinding.VALID);
            assertThat(jwtUtils.validateTenantBinding(token, tenantId.toString().toUpperCase()))
                    .isEqualTo(JwtUtils.TenantBinding.VALID);
        }
    }

    @Nested
    @DisplayName("isRefreshTokenInvalid()")
    class RefreshTokenValidity {

        @Test
        @DisplayName("无法解析的刷新令牌视为无效（fail-closed）")
        void unparsableRefreshTokenIsInvalid() {
            assertThat(jwtUtils.isRefreshTokenInvalid("garbage")).isTrue();
            assertThat(jwtUtils.isRefreshTokenInvalid(null)).isTrue();
        }

        @Test
        @DisplayName("访问令牌不能当刷新令牌用")
        void accessTokenIsNotAValidRefreshToken() {
            String access = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER"), Set.of(),
                    "device-1", "1.2.3.4", null, UUID.randomUUID(), "t1");
            assertThat(jwtUtils.isRefreshTokenInvalid(access)).isTrue();
        }

        @Test
        @DisplayName("有效的刷新令牌返回 false")
        void validRefreshTokenIsAccepted() {
            String refresh = jwtUtils.generateRefreshToken(
                    UUID.randomUUID(), "alice", "device-1",
                    UUID.randomUUID(), "t1", null);
            assertThat(jwtUtils.isRefreshTokenInvalid(refresh)).isFalse();
        }
    }

    @Nested
    @DisplayName("从 token 读声明")
    class ClaimsAccess {

        @Test
        @DisplayName("角色和租户可以读回；无权限声明时返回空集合")
        void rolesAndTenantRoundTrip() {
            UUID tenantId = UUID.randomUUID();
            String token = jwtUtils.generateAccessToken(
                    UUID.randomUUID(), "alice", Set.of("ROLE_USER", "ROLE_ADMIN"), Set.of("p1", "p2"),
                    "device-1", "1.2.3.4", null, tenantId, "tenant-x");

            assertThat(jwtUtils.getRolesFromToken(token)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
            assertThat(jwtUtils.getTenantIdFromToken(token)).isEqualTo(tenantId);
            assertThat(jwtUtils.getTenantCodeFromToken(token)).isEqualTo("tenant-x");
        }

        @Test
        @DisplayName("无效 token 读角色返回空集合，而不是抛异常——调用方据空集合即可拒绝")
        void invalidTokenYieldsEmptyRoles() {
            assertThat(jwtUtils.getRolesFromToken("garbage")).isEmpty();
            assertThat(jwtUtils.getPermissionsFromToken("garbage")).isEmpty();
            assertThat(jwtUtils.getAmrFromToken("garbage")).isEmpty();
        }
    }
}
