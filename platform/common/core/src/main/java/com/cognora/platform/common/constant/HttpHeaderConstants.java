package com.cognora.platform.common.constant;

/**
 * 请求头名称常量。
 *
 * <p>这些名字此前散落在各模块的字面量里：{@code X-Request-ID} 有三份互相独立的常量，
 * {@code X-Device-ID} 甚至有 {@code X-Device-Id} 两种写法。HTTP 头名按 RFC 9110 大小写不敏感，
 * 所以拼错不会报错，只会在链路某一跳上悄悄取不到值。集中到这里之后：
 * <ul>
 *   <li>跨模块（core / web / gateway / agent / audit / inventory）共用同一份定义；</li>
 *   <li>名字写错在编译期就能看出来，而不是等到运行时才发现某跳失效。</li>
 * </ul>
 *
 * <p>签名头的名字虽然到处都在用，但<em>签名内容</em>在网关、服务间通道、Agent 运行时三处各不相同，
 * 所以这里只统一名字，不在这里统一算法。
 */
public final class HttpHeaderConstants {

    private HttpHeaderConstants() {
    }

    // ==================== 通用 ====================

    /** 链路追踪 ID。请求与响应都会带上。 */
    public static final String REQUEST_ID = "X-Request-ID";

    /** 租户 ID。 */
    public static final String TENANT_ID = "X-Tenant-Id";

    /** 租户 ID 的备用写法（部分调用方仍用不带 {@code X-} 前缀的形式）。 */
    public static final String TENANT_ID_ALT = "Tenant-Id";

    /** 认证令牌。 */
    public static final String AUTHORIZATION = "Authorization";

    /** 请求体类型。 */
    public static final String CONTENT_TYPE = "Content-Type";

    // ==================== 签名通道 ====================

    /** 签名时间戳（毫秒）。 */
    public static final String SIGNATURE_TIMESTAMP = "X-Timestamp";

    /** 签名随机串；同一次业务写入重试时必须复用同一个值。 */
    public static final String SIGNATURE_NONCE = "X-Nonce";

    /** 签名值。 */
    public static final String SIGNATURE = "X-Signature";

    /** 签名方标识（不是密钥）。 */
    public static final String SIGNATURE_APP_ID = "X-App-Id";

    /** 签名算法版本（网关的多版本签名用）。 */
    public static final String SIGNATURE_VERSION = "X-Sign-Version";

    // ==================== 委派身份（网关 → 服务间调用方 → Agent 运行时） ====================

    /** 委派给下游的用户 ID。 */
    public static final String USER_ID = "X-User-Id";

    /** 委派给下游的用户角色。 */
    public static final String USER_ROLE = "X-User-Role";

    /** 下游用来换取用户信息的平台令牌。 */
    public static final String PLATFORM_TOKEN = "X-Platform-Token";

    /** 客户端设备 ID。 */
    public static final String DEVICE_ID = "X-Device-ID";

    /** 客户端时区（IANA 名称，如 Asia/Shanghai），用于登录会话的地区展示。 */
    public static final String TIMEZONE = "X-Timezone";

    /** 幂等键：同一次业务写入重试时必须复用同一个值。 */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
}
