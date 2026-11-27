package com.cognora.platform.common.rest.signature;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.HmacAlgorithm;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The canonical form of an internal-service signature, shared by the signer
 * ({@code RestClientRequestSignatureInterceptor}) and every verifier
 * ({@code InternalSignatureVerifier}, {@code AuditWriteRequestGuard}).
 *
 * <p>Signing content is
 * <pre>timestamp + nonce + appId + uri + canonicalQuery</pre>
 * and the signature is {@code HMAC-SHA256(secretKey, signingContent)} in lower-case hex.
 *
 * <p>Keeping the canonical form in one place is what stops the two halves of the channel from
 * drifting: a mismatch here rejects every service-to-service call, not just the odd one.
 */
public final class InternalSignatureCanonicalizer {

    private InternalSignatureCanonicalizer() {
    }

    /**
     * Canonical query string: keys and values are percent-DECODED before being sorted and joined.
     *
     * <p>The signer reads {@code URI.getQuery()}, which is already decoded, so the verifier has to
     * decode the raw wire query the same way. Percent-decoding both sides keeps them in
     * agreement; a malformed escape falls back to the raw text instead of raising, so a broken
     * request fails the signature check instead of the whole filter.
     *
     * @param rawQuery the raw query string as it arrived on the wire
     * @return {@code key1=value1&key2=value2}, sorted by key
     */
    public static String canonicalQuery(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return "";
        }
        Map<String, String> params = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
        }
        return canonicalParams(params);
    }

    /**
     * Canonical form of already decoded parameters: sorted by key and joined with {@code &}.
     *
     * @param decodedParams the parameters, keys and values already decoded
     * @return {@code key1=value1&key2=value2}, sorted by key
     */
    public static String canonicalParams(Map<String, String> decodedParams) {
        if (decodedParams == null || decodedParams.isEmpty()) {
            return "";
        }
        return decodedParams.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    /**
     * The exact string that gets signed.
     */
    public static String signingContent(String timestamp, String nonce, String appId, String uri,
                                        String canonicalQuery) {
        return timestamp + nonce + appId + uri + canonicalQuery;
    }

    /**
     * HMAC-SHA256 of the signing content, lower-case hex.
     */
    public static String hmacHex(String secretKey, String signingContent) {
        return SecureUtil.hmac(HmacAlgorithm.HmacSHA256, secretKey.getBytes(StandardCharsets.UTF_8))
                .digestHex(signingContent);
    }

    /**
     * Percent-decode one query part. The decoding mirrors {@code URI.getQuery()}: {@code %XX}
     * escapes are resolved, a literal {@code +} stays a plus sign.
     */
    private static String decode(String value) {
        try {
            return UriUtils.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            // Malformed escape: keep the raw text so the signature fails closed instead of 500.
            return value;
        }
    }
}
