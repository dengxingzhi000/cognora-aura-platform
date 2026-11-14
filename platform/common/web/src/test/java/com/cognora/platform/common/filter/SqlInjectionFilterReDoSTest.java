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
        // MAX_SCAN_LENGTH (4096) before matching.
        //
        // The property that matters is *scaling*, not a wall-clock budget: once the
        // input is truncated, quadrupling the payload must not cost more than about
        // the same work. An absolute threshold (this test used to assert <200ms)
        // measures the CI machine's mood as much as the filter — it passed alone and
        // failed in the full suite, where the box is busy — and a flaky security test
        // gets deleted rather than trusted.
        String smallPayload = "/*\n".repeat(5_000);    // 15 KB, well past the 4 KB cap
        String largePayload = "/*\n".repeat(20_000);   // 60 KB, 4x the input

        long smallMillis = timeBlockedThrough(filter, smallPayload);
        long largeMillis = timeBlockedThrough(filter, largePayload);

        // Quadratic backtracking would make this ~16x; truncation keeps it flat.
        // A generous 4x factor leaves room for GC and scheduling noise while still
        // failing loudly if the cap is ever removed.
        long allowed = Math.max(smallMillis, 50L) * 4L;
        assertTrue(largeMillis <= allowed,
                "Time grew with input size (15KB=" + smallMillis + "ms, 60KB=" + largeMillis
                        + "ms, allowed " + allowed + "ms): the MAX_SCAN_LENGTH truncation is not"
                        + " being applied, so the payload is reaching the regex untruncated.");

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        filter.doFilter(queryRequest("q", largePayload), response, (request, filteredResponse) -> proceeded.set(true));

        assertTrue(proceeded.get(), "An unterminated comment carries no injection signature, so it must pass through");
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    /** Runs the filter once over {@code payload} and returns how long it took, in ms. */
    private static long timeBlockedThrough(SqlInjectionFilter filter, String payload)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        long startedAt = System.nanoTime();
        filter.doFilter(queryRequest("q", payload), response, (request, filteredResponse) -> {
        });
        return (System.nanoTime() - startedAt) / 1_000_000L;
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
