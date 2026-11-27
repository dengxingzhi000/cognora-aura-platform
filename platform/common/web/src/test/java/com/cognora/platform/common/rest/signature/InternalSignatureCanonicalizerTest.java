package com.cognora.platform.common.rest.signature;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InternalSignatureCanonicalizerTest {

    @Test
    void sortsTheParametersByKey() {
        assertThat(InternalSignatureCanonicalizer.canonicalQuery("b=2&a=1")).isEqualTo("a=1&b=2");
    }

    @Test
    void decodesTheWireFormBeforeSorting() {
        assertThat(InternalSignatureCanonicalizer.canonicalQuery("url=%2Fapi%2Fv1&method=GET"))
                .isEqualTo("method=GET&url=/api/v1");
    }

    /**
     * The signer signs {@code URI.getQuery()}, which keeps a literal {@code +} as a plus sign.
     * The verifier therefore has to decode the same way, otherwise every parameter containing a
     * plus sign is rejected with 403 even though both sides share the same secret.
     */
    @Test
    void keepsALiteralPlusSignAsTheSignerDoes() {
        assertThat(InternalSignatureCanonicalizer.canonicalQuery("q=a+b")).isEqualTo("q=a+b");
    }

    @Test
    void fallsBackToTheRawTextForAMalformedEscape() {
        assertThat(InternalSignatureCanonicalizer.canonicalQuery("q=%zz")).isEqualTo("q=%zz");
    }

    @Test
    void treatsAMissingValueAsEmpty() {
        assertThat(InternalSignatureCanonicalizer.canonicalQuery("flag")).isEqualTo("flag=");
    }

    @Test
    void returnsAnEmptyStringWithoutAQuery() {
        assertThat(InternalSignatureCanonicalizer.canonicalQuery(null)).isEmpty();
        assertThat(InternalSignatureCanonicalizer.canonicalQuery("  ")).isEmpty();
    }

    @Test
    void sortsAlreadyDecodedParametersTheSameWay() {
        Map<String, String> decoded = new LinkedHashMap<>();
        decoded.put("url", "/api/v1");
        decoded.put("method", "GET");

        assertThat(InternalSignatureCanonicalizer.canonicalParams(decoded))
                .isEqualTo("method=GET&url=/api/v1");
    }

    @Test
    void buildsTheSameSigningContentAsTheDocumentedFormat() {
        assertThat(InternalSignatureCanonicalizer.signingContent("123", "nonce", "app", "/api/v1", "a=1"))
                .isEqualTo("123nonceapp/api/v1a=1");
    }
}
