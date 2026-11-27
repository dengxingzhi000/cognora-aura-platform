package com.cognora.platform.common.log.util;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 日志工具：MDC 上下文 + 结构化日志 + 日志输入净化。
 *
 * <p>MDC 的键与 {@code TenantAwareMDCTaskDecorator} 保持一致，异步线程才能继承上下文。</p>
 *
 * <p>结构化日志（{@code business} / {@code api} / {@code rpc}）输出
 * {@code <TYPE>_LOG: {json}}，其中 json 的字段顺序固定，先 type 再业务字段最后 timestamp，
 * 方便日志采集侧按 key 解析。</p>
 */
@Slf4j
public final class LogUtils {

    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_TRACE_ID = "traceId";
    private static final String KEY_REQUEST_ID = "requestId";

    /** 单个日志值的长度上限，超出部分截断。 */
    private static final int MAX_LOG_VALUE_LENGTH = 256;

    private LogUtils() {
    }

    /**
     * 设置用户上下文。
     */
    public static void setUserContext(Long userId, String username) {
        if (userId != null) {
            MDC.put(KEY_USER_ID, userId.toString());
        }
        if (StringUtils.hasText(username)) {
            MDC.put(KEY_USERNAME, username);
        }
    }

    /**
     * 设置追踪 ID。
     */
    public static void setTraceId(String traceId) {
        if (StringUtils.hasText(traceId)) {
            MDC.put(KEY_TRACE_ID, traceId);
        }
    }

    /**
     * 设置请求 ID。
     */
    public static void setRequestId(String requestId) {
        if (StringUtils.hasText(requestId)) {
            MDC.put(KEY_REQUEST_ID, requestId);
        }
    }

    /**
     * 清除 MDC。
     */
    public static void clear() {
        MDC.clear();
    }

    /**
     * 日志输入净化：替换换行符与控制字符，并截断超长内容。
     * <p>
     * 用于把请求参数、请求体字段等外部输入写入日志前，避免 CRLF 注入伪造日志行。
     * 返回 {@code String.valueOf(value)}，因此 {@code null} 仍输出为 {@code "null"}。
     *
     * @param value 待写入日志的外部输入
     * @return 不含换行/控制字符且长度受限的文本
     */
    public static String sanitizeForLog(Object value) {
        String text = String.valueOf(value);
        String sanitized = replaceControlCharacters(text);
        if (sanitized.length() > MAX_LOG_VALUE_LENGTH) {
            return sanitized.substring(0, MAX_LOG_VALUE_LENGTH) + "...";
        }
        return sanitized;
    }

    /**
     * 结构化日志：业务日志。
     */
    public static void business(String action, String result, Object data) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("action", action);
        fields.put("result", result);
        fields.put("data", data);
        logStructured("business", fields);
    }

    /**
     * 结构化日志：接口调用。
     */
    public static void api(String method, String uri, long duration, int status) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", method);
        fields.put("uri", uri);
        fields.put("duration", duration);
        fields.put("status", status);
        logStructured("api", fields);
    }

    /**
     * 结构化日志：RPC 调用。
     */
    public static void rpc(String service, String method, long duration, boolean success, String error) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("service", service);
        fields.put("method", method);
        fields.put("duration", duration);
        fields.put("success", success);
        fields.put("error", error);
        logStructured("rpc", fields);
    }

    private static void logStructured(String type, Map<String, Object> fields) {
        // INFO 关闭时不必再把字段序列化成 JSON：这一步在每个请求的日志路径上都会执行。
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("{}_LOG: {}", type.toUpperCase(Locale.ROOT),
                structuredJson(type, fields, System.currentTimeMillis()));
    }

    /**
     * 结构化日志的 JSON 载荷：先 type，再业务字段，最后 timestamp——顺序固定，便于采集侧解析。
     */
    static String structuredJson(String type, Map<String, Object> fields, long timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.putAll(fields);
        payload.put("timestamp", timestamp);
        return JSON.toJSONString(payload);
    }

    /**
     * 单遍扫描替换控制字符（{@code U+0000}-{@code U+001F} 与 {@code DEL}）。
     *
     * <p>这里刻意不用正则：{@code replaceAll} 每次调用都要重新编译一次模式，而
     * {@code sanitizeForLog} 位于每个带外部入参的日志语句上，属于热路径。</p>
     */
    private static String replaceControlCharacters(String text) {
        int length = text.length();
        StringBuilder builder = null;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            boolean control = c < 0x20 || c == 0x7F;
            if (builder == null) {
                if (!control) {
                    continue;
                }
                builder = new StringBuilder(length).append(text, 0, i);
            }
            builder.append(control ? '_' : c);
        }
        return builder == null ? text : builder.toString();
    }
}
