package com.cognora.platform.audit.security;

import com.cognora.platform.common.constant.HttpHeaderConstants;
import com.cognora.platform.common.log.util.LogUtils;
import com.cognora.platform.common.rest.signature.InternalSignatureVerifier;
import com.cognora.platform.common.security.metrics.SecurityMetrics;
import com.cognora.platform.common.security.util.RequestPathMatcher;
import com.cognora.platform.common.security.util.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 审计写入入口的调用方校验。
 *
 * <p>{@code POST /sys-audit-log} 是 scm-common 的 {@code SysAuditServiceClient} 使用的服务间写入通道，
 * 调用方会携带 {@code RestClientRequestSignatureInterceptor} 生成的 HMAC 头。本过滤器只放行带合法
 * 签名的内部调用，防止任意登录用户伪造/删除审计记录。校验算法与拦截器保持一致：
 * <pre>signature = HMAC-SHA256(secretKey, timestamp + nonce + appId + uri + sortedParams)</pre>
 *
 * <p>签名校验本身由 {@link InternalSignatureVerifier} 完成——它与签名方共用同一个规范化实现
 * {@link com.cognora.platform.common.rest.signature.InternalSignatureCanonicalizer} 和
 * {@link com.cognora.platform.common.rest.signature.InternalSignatureSecretResolver} 解析出的密钥，
 * 因此这里不再重复一份 HMAC 逻辑；缺失密钥时 {@link AuditWriteSecurityValidator} 会让服务直接启动失败，
 * 而不是退回到仓库里的常量。
 *
 * <p>路径匹配按 Spring MVC 的规则归一化（解码、去掉矩阵参数、去掉末尾斜杠），
 * {@code /sys-audit-log;x=1}、{@code /%73ys-audit-log} 这类变体不会绕过签名校验。
 *
 * <p>未校验 nonce 重放：scm-audit 不依赖 Redis。时间戳窗口限制为 5 分钟。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditWriteRequestGuard extends OncePerRequestFilter {

    static final String WRITE_PATH = "/sys-audit-log";

    /** 被拒绝的写入次数，运维在 scm-audit 上观察该指标。 */
    static final String METRIC_REJECTED = "security.audit.write.rejected";

    private final InternalSignatureVerifier signatureVerifier;
    private final SecurityMetrics securityMetrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        return !isAuditWritePath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!hasValidSignature(request)) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    boolean hasValidSignature(HttpServletRequest request) {
        return signatureVerifier.hasValidSignature(request);
    }

    /**
     * 判断请求是否指向审计写入入口。
     *
     * <p>Spring MVC 用「解码后、去掉矩阵参数」的路径做匹配，所以 {@code /sys-audit-log;x=1}、
     * {@code /%73ys-audit-log} 与 {@code /sys-audit-log/} 都会落到同一个 Controller。直接比较
     * {@code getRequestURI()} 会让这些变体绕过签名校验。
     */
    private boolean isAuditWritePath(String rawUri) {
        String normalized = RequestPathMatcher.normalizePath(rawUri);
        // 无法解码的路径按写入尝试处理（fail closed）：宁可误拒，也不能放过。
        return normalized == null || WRITE_PATH.equals(normalized);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        securityMetrics.increment(METRIC_REJECTED);
        log.error("Rejected audit write: appId={}, remote={}, uri={}, traceId={}",
                LogUtils.sanitizeForLog(request.getHeader(HttpHeaderConstants.SIGNATURE_APP_ID)),
                LogUtils.sanitizeForLog(request.getRemoteAddr()),
                LogUtils.sanitizeForLog(request.getRequestURI()),
                LogUtils.sanitizeForLog(request.getHeader(HttpHeaderConstants.REQUEST_ID)));
        SecurityErrorResponseWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                "ACCESS_DENIED", "Audit writes are restricted to internal services");
    }
}
