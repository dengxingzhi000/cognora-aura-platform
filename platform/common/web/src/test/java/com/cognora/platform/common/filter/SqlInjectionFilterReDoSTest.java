package com.cognora.platform.common.filter;

import com.cognora.platform.common.security.config.SecurityFilterProperties;
import com.cognora.platform.common.security.filter.SqlInjectionFilter;
import com.cognora.platform.common.security.metrics.SecurityMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The security filter tests under {@code .../security/**} are excluded from
 * test compilation by {@code platform-common-web}'s POM, so the ReDoS
 * regression lives in this package to actually run in CI.
 */
class SqlInjectionFilterReDoSTest {

    private static SqlInjectionFilter blockingFilter() {
        SecurityFilterProperties properties = new SecurityFilterProperties();
        properties.setMode(SecurityFilterProperties.SqlFilterMode.BLOCK);
        return new SqlInjectionFilter(properties, new SecurityMetrics(new SimpleMeterRegistry()));
    }

    private static MockHttpServletRequest queryRequest(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/search");
        request.addParameter(name, value);
        return request;
    }

    @Test
    void staysFastOnLongUnterminatedCommentPayload() throws ServletException, IOException {
        SqlInjectionFilter filter = blockingFilter();

        // Warm up the JIT and the regex engine with a harmless value.
        filter.doFilter(queryRequest("q", "laptop"), new MockHttpServletResponse(), (request, response) -> {
        });

        // "/*" repeated without a closing "*/" used to backtrack quadratically
        // through (?:.|[\n\r])*?. The scan input is now truncated to
        // MAX_SCAN_LENGTH (4096) before matching, so this must return immediately.
        String payload = "/*\n".repeat(20_000);

        // The regression is quadratic/unbounded backtracking, so the payload is timed against
        // itself at two sizes: doubling the input must not much more than double the time. That
        // scaling check is what distinguishes "linear on a truncated buffer" from the original
        // bug, and unlike an absolute millisecond budget it does not fire on a loaded CI runner
        // (the old fixed 200ms threshold failed at 312ms under concurrent Maven load while the
        // filter was behaving exactly as designed).
        long smallNanos = timeOnce(filter, "/*\n".repeat(5_000));
        long largeNanos = timeOnce(filter, payload);

        // Allow generous constant factors: allocation, GC and scheduling noise all land here.
        // A quadratic matcher would be ~16x for this 4x input growth; linear is ~4x.
        long budget = Math.max(smallNanos * 8, 50_000_000L); // *8, floor 50ms
        assertTrue(largeNanos < budget,
                "Pathological payload scaling looks superlinear: 5k took " + (smallNanos / 1_000_000L)
                        + "ms, 20k took " + (largeNanos / 1_000_000L) + "ms, budget "
                        + (budget / 1_000_000L) + "ms");
    }

    /**
     * Times one pass of the filter over {@code payload} and asserts it still passes through
     * (an unterminated comment carries no injection signature).
     */
    private static long timeOnce(SqlInjectionFilter filter, String payload)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);

        long startedAt = System.nanoTime();
        filter.doFilter(queryRequest("q", payload), response, (request, filteredResponse) -> proceeded.set(true));
        long elapsedNanos = System.nanoTime() - startedAt;

        assertTrue(proceeded.get(), "An unterminated comment carries no injection signature, so it must pass through");
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        return elapsedNanos;
    }

    @Test
    void stillBlocksKnownInjectionSignatures() throws ServletException, IOException {
        SqlInjectionFilter filter = blockingFilter();
        List<String> payloads = List.of(
                "1' OR 1=1 --",
                "1 union select password from users",
                "1 UNION ALL SELECT 1,2,3",
                "'; drop table users; --",
                "/*!50000select*/ 1"
        );

        for (String payload : payloads) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean proceeded = new AtomicBoolean(false);

            filter.doFilter(queryRequest("q", payload), response, (request, filteredResponse) -> proceeded.set(true));

            assertFalse(proceeded.get(), "Payload should be blocked: " + payload);
            assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus(), "Payload: " + payload);
            assertTrue(response.getContentAsString().contains("SQL_INJECTION"), "Payload: " + payload);
        }
    }
}
