package com.cognora.platform.common.security.util;

import java.util.Locale;

/**
 * 从 User-Agent 提取可读的设备名与设备类型。
 *
 * <p>登录会话列表用这两个字段展示“哪台设备”，避免只能看到一串 deviceId。
 * 解析只覆盖主流浏览器/系统，未知 UA 返回 {@code null}，由前端回落为通用文案。</p>
 */
public final class UserAgentUtils {

    private UserAgentUtils() {
    }

    /**
     * @param deviceName 形如 {@code Chrome · Windows}；无法识别时为 {@code null}
     * @param deviceType {@code web} / {@code mobile} / {@code desktop}；无法识别时为 {@code null}
     */
    public record ClientInfo(String deviceName, String deviceType) {
    }

    public static ClientInfo describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new ClientInfo(null, null);
        }

        String browser = detectBrowser(userAgent);
        String os = detectOs(userAgent);
        String name;
        if (browser != null && os != null) {
            name = browser + " · " + os;
        } else {
            name = browser != null ? browser : os;
        }
        return new ClientInfo(name, detectType(userAgent));
    }

    private static String detectBrowser(String ua) {
        String value = ua.toLowerCase(Locale.ROOT);
        if (value.contains("edg/") || value.contains("edga/") || value.contains("edgios/")) {
            return "Edge";
        }
        if (value.contains("opr/") || value.contains("opera")) {
            return "Opera";
        }
        if (value.contains("crios/") || value.contains("chrome/")) {
            return "Chrome";
        }
        if (value.contains("fxios/") || value.contains("firefox/")) {
            return "Firefox";
        }
        if (value.contains("version/") && value.contains("safari/")) {
            return "Safari";
        }
        if (value.contains("postmanruntime")) {
            return "Postman";
        }
        if (value.contains("curl/")) {
            return "curl";
        }
        return null;
    }

    private static String detectOs(String ua) {
        String value = ua.toLowerCase(Locale.ROOT);
        if (value.contains("windows")) {
            return "Windows";
        }
        if (value.contains("iphone") || value.contains("ipad") || value.contains("ipod")) {
            return "iOS";
        }
        if (value.contains("android")) {
            return "Android";
        }
        if (value.contains("macintosh") || value.contains("mac os x")) {
            return "macOS";
        }
        if (value.contains("linux")) {
            return "Linux";
        }
        return null;
    }

    private static String detectType(String ua) {
        String value = ua.toLowerCase(Locale.ROOT);
        if (value.contains("electron") || value.contains("cognoradesktop")) {
            return "desktop";
        }
        if (value.contains("android") || value.contains("iphone") || value.contains("ipad")
                || value.contains("ipod") || value.contains("mobi")) {
            return "mobile";
        }
        return "web";
    }
}