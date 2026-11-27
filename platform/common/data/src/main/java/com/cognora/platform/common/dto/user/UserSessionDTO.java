package com.cognora.platform.common.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 当前用户的登录会话视图，对应前端 {@code AuthSessionDTO}。
 *
 * <p>只暴露设备标识、设备信息、IP 与时间信息，不返回访问令牌本身。设备名与设备类型
 * 由登录时采集的 User-Agent 解析；地区优先使用客户端时区（浏览器上报的 IANA 时区），
 * 内网/本机地址统一显示为「本地网络」。</p>
 *
 * @author Deng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSessionDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 会话标识：令牌的 deviceId 声明。 */
    private String sessionId;

    /** 设备名，例如 {@code Chrome · Windows}；无法识别时为 {@code null}。 */
    private String deviceName;

    /** {@code web} / {@code mobile} / {@code desktop}；无法识别时为 {@code null}。 */
    private String deviceType;

    /** 签发令牌时的客户端 IP。 */
    private String ip;

    /** 地区：内网为「本地网络」，否则取客户端时区的本地化名称。 */
    private String location;

    /** 登录时的原始 User-Agent，排障用。 */
    private String userAgent;

    /** 最近活跃时间：取令牌最近一次签发/刷新时间。 */
    private Instant lastActiveAt;

    /** 是否为当前请求令牌所对应的会话。 */
    private boolean current;
}