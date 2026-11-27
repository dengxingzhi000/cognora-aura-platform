package com.cognora.platform.common.rest.signature;

import com.cognora.platform.common.constant.HttpHeaderConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifies the HMAC signature of a service-to-service request.
 *
 * <p>The algorithm mirrors {@code RestClientRequestSignatureInterceptor} exactly:
 * <pre>signature = HMAC-SHA256(secretKey, timestamp + nonce + appId + requestURI + sortedParams)</pre>
 *
 * <p>The canonical form lives in {@link InternalSignatureCanonicalizer}, which the signer and every
 * verifier share — that is what keeps the two halves of the channel from drifting apart.
 *
 * <p>The secret is resolved through {@link InternalSignatureSecretResolver}; a blank secret makes
 * every verification fail closed. Timestamps outside a five minute window are rejected. The nonce
 * is not checked for replay here (no shared Redis), only the clock window.
 */
@Component
@RequiredArgsConstructor
public class InternalSignatureVerifier {

    private static final long MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1000L;

    private final InternalSignatureSecretResolver secretResolver;

    public boolean hasValidSignature(HttpServletRequest request) {
        String secretKey = secretResolver.resolveSecret();
        if (!StringUtils.hasText(secretKey)) {
            return false;
        }
        String timestamp = request.getHeader(HttpHeaderConstants.SIGNATURE_TIMESTAMP);
        String nonce = request.getHeader(HttpHeaderConstants.SIGNATURE_NONCE);
        String signature = request.getHeader(HttpHeaderConstants.SIGNATURE);
        String appId = request.getHeader(HttpHeaderConstants.SIGNATURE_APP_ID);
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(signature) || !StringUtils.hasText(appId)) {
            return false;
        }
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(System.currentTimeMillis() - requestTime) > MAX_CLOCK_SKEW_MILLIS) {
            return false;
        }

        String signingContent = InternalSignatureCanonicalizer.signingContent(
                timestamp, nonce, appId, request.getRequestURI(),
                InternalSignatureCanonicalizer.canonicalQuery(request.getQueryString()));
        String expected = InternalSignatureCanonicalizer.hmacHex(secretKey, signingContent);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
