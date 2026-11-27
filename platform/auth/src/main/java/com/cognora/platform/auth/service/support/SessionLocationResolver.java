package com.cognora.platform.auth.service.support;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 会话“地区”展示值的解析器。
 *
 * <p>平台没有离线 GeoIP 库，也不把用户 IP 发给第三方，因此地区来自登录请求里
 * 客户端上报的 IANA 时区（浏览器 {@code Intl.DateTimeFormat().resolvedOptions().timeZone}）。
 * 内网/回环地址直接显示「本地网络」；没有时区信息时保持 {@code null}，不伪造城市。</p>
 */
public final class SessionLocationResolver {

    private static final String LOCAL_NETWORK = "本地网络";

    private SessionLocationResolver() {
    }

    public static String resolve(String ipAddress, String timezone) {
        if (isLocalAddress(ipAddress)) {
            return LOCAL_NETWORK;
        }
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        String zone = timezone.trim();
        try {
            return ZoneId.of(zone).getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE);
        } catch (DateTimeException e) {
            // 客户端上报了非标准时区：原样展示，至少比空白可读。
            return zone;
        }
    }

    private static boolean isLocalAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }
        // X-Forwarded-For 可能是逗号分隔链，只取最前面的客户端地址。
        String value = ipAddress.split(",")[0].trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            value = end > 0 ? value.substring(1, end) : value.substring(1);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || "::1".equals(lower) || "0:0:0:0:0:0:0:1".equals(lower)) {
            return true;
        }
        if (lower.startsWith("fe80:") || lower.startsWith("fc") || lower.startsWith("fd")) {
            return true;
        }
        if (value.startsWith("127.") || value.startsWith("10.")
                || value.startsWith("192.168.") || value.startsWith("169.254.")) {
            return true;
        }
        if (value.startsWith("172.")) {
            int secondDot = value.indexOf('.', 4);
            if (secondDot > 0) {
                try {
                    int second = Integer.parseInt(value.substring(4, secondDot));
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }
}