package com.cognora.platform.common.rest.signature;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.HmacAlgorithm;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InternalSignatureVerifierTest {

    private static final String SECRET = "unit-test-internal-secret-0123456789abcdef";
    private static final String APP_ID = "internal-service";
    private static final String URI = "/api/v1/inventory/reservation";

    @Test
    void acceptsAValidSignature() {
        InternalSignatureVerifier verifier = verifierWithSecret(SECRET);
        MockHttpServletRequest request = signedRequest(System.currentTimeMillis(), "nonce-1", "a=1&b=2");

        assertThat(verifier.hasValidSignature(request)).isTrue();
    }

    @Test
    void rejectsMissingSignatureHeaders() {
        InternalSignatureVerifier verifier = verifierWithSecret(SECRET);

        assertThat(verifier.hasValidSignature(new MockHttpServletRequest("POST", URI))).isFalse();
    }

    @Test
    void rejectsAWrongSignature() {
        InternalSignatureVerifier verifier = verifierWithSecret(SECRET);
        MockHttpServletRequest request = signedRequest(System.currentTimeMillis(), "nonce-2", null);
        request.removeHeader("X-Signature");
        request.addHeader("X-Signature", "deadbeef");

        assertThat(verifier.hasValidSignature(request)).isFalse();
    }

    @Test
    void rejectsAStaleTimestamp() {
        InternalSignatureVerifier verifier = verifierWithSecret(SECRET);
        MockHttpServletRequest request = signedRequest(
                System.currentTimeMillis() - 6 * 60 * 1000L, "nonce-3", null);

        assertThat(verifier.hasValidSignature(request)).isFalse();
    }

    @Test
    void rejectsANonNumericTimestamp() {
        InternalSignatureVerifier verifier = verifierWithSecret(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", URI);
        request.addHeader("X-Timestamp", "not-a-number");
        request.addHeader("X-Nonce", "nonce");
        request.addHeader("X-App-Id", APP_ID);
        request.addHeader("X-Signature", "deadbeef");

        assertThat(verifier.hasValidSignature(request)).isFalse();
    }

    @Test
    void failsClosedWhenTheSecretIsMissing() {
        InternalSignatureVerifier verifier = verifierWithSecret("");
        MockHttpServletRequest request = signedRequest(System.currentTimeMillis(), "nonce-4", null);

        assertThat(verifier.hasValidSignature(request)).isFalse();
    }

    @Test
    void signatureCoversTheSortedQueryString() {
        InternalSignatureVerifier verifier = verifierWithSecret(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", URI);
        request.setQueryString("b=2&a=1");
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-5";
        request.addHeader("X-Timestamp", String.valueOf(timestamp));
        request.addHeader("X-Nonce", nonce);
        request.addHeader("X-App-Id", APP_ID);
        request.addHeader("X-Signature",
                sign(timestamp, nonce, APP_ID, URI, "a=1&b=2"));

        assertThat(verifier.hasValidSignature(request)).isTrue();
    }

    /**
     * The signer reads {@code URI.getQuery()}, which is already percent-decoded, so the verifier
     * must decode the raw wire query too. Before this was fixed, a call whose {@code url} parameter
     * contained {@code /} (encoded as {@code %2F} on the wire) was rejected with 403 even though
     * both sides shared the same secret - the whole permission lookup channel failed closed.
     */
    @Test
    void acceptsASignatureOverPercentEncodedParameters() {
        InternalSignatureVerifier verifier = verifierWithSecret(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URI);
        // Wire form the container receives: values are still percent-encoded.
        request.setQueryString("url=%2Fapi%2Fv1%2Forders%2Fdashboard%2Fsummary&method=GET");
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-6";
        request.addHeader("X-Timestamp", String.valueOf(timestamp));
        request.addHeader("X-Nonce", nonce);
        request.addHeader("X-App-Id", APP_ID);
        // The caller signed the DECODED, sorted parameters.
        request.addHeader("X-Signature",
                sign(timestamp, nonce, APP_ID, URI, "method=GET&url=/api/v1/orders/dashboard/summary"));

        assertThat(verifier.hasValidSignature(request)).isTrue();
    }

    @Test
    void rejectsASignatureOverTheRawEncodedParameters() {
        InternalSignatureVerifier verifier = verifierWithSecret(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URI);
        request.setQueryString("url=%2Fapi%2Fv1%2Forders%2Fdashboard%2Fsummary&method=GET");
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-7";
        request.addHeader("X-Timestamp", String.valueOf(timestamp));
        request.addHeader("X-Nonce", nonce);
        request.addHeader("X-App-Id", APP_ID);
        // Signing the raw wire text must NOT validate: it is the mismatched convention.
        request.addHeader("X-Signature",
                sign(timestamp, nonce, APP_ID, URI,
                        "method=GET&url=%2Fapi%2Fv1%2Forders%2Fdashboard%2Fsummary"));

        assertThat(verifier.hasValidSignature(request)).isFalse();
    }

    private static InternalSignatureVerifier verifierWithSecret(String secret) {
        MockEnvironment environment = new MockEnvironment();
        if (!secret.isEmpty()) {
            environment.setProperty("security.signature.app-secrets.internal-service", secret);
        }
        return new InternalSignatureVerifier(new InternalSignatureSecretResolver(environment));
    }

    static MockHttpServletRequest signedRequest(long timestamp, String nonce, String query) {
        return signedRequest(timestamp, nonce, query, URI);
    }

    static MockHttpServletRequest signedRequest(long timestamp, String nonce, String query, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (query != null) {
            request.setQueryString(query);
        }
        String sorted = sortAndConcat(query);
        request.addHeader("X-Timestamp", String.valueOf(timestamp));
        request.addHeader("X-Nonce", nonce);
        request.addHeader("X-App-Id", APP_ID);
        request.addHeader("X-Signature", sign(timestamp, nonce, APP_ID, uri, sorted));
        return request;
    }

    private static String sign(long timestamp, String nonce, String appId, String uri, String sortedParams) {
        String content = timestamp + nonce + appId + uri + sortedParams;
        return SecureUtil.hmac(HmacAlgorithm.HmacSHA256, SECRET.getBytes(StandardCharsets.UTF_8))
                .digestHex(content);
    }

    private static String sortAndConcat(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        java.util.Map<String, String> params = new java.util.TreeMap<>();
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }
}
