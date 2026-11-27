package com.cognora.platform.common.security.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserAgentUtilsTest {

    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String SAFARI_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String EDGE_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";

    @Test
    void describesDesktopChrome() {
        UserAgentUtils.ClientInfo info = UserAgentUtils.describe(CHROME_WINDOWS);

        assertEquals("Chrome · Windows", info.deviceName());
        assertEquals("web", info.deviceType());
    }

    @Test
    void describesMobileSafari() {
        UserAgentUtils.ClientInfo info = UserAgentUtils.describe(SAFARI_IPHONE);

        assertEquals("Safari · iOS", info.deviceName());
        assertEquals("mobile", info.deviceType());
    }

    @Test
    void prefersEdgeOverChromeToken() {
        UserAgentUtils.ClientInfo info = UserAgentUtils.describe(EDGE_WINDOWS);

        assertEquals("Edge · Windows", info.deviceName());
    }

    @Test
    void returnsNullsForMissingUserAgent() {
        UserAgentUtils.ClientInfo info = UserAgentUtils.describe(" ");

        assertNull(info.deviceName());
        assertNull(info.deviceType());
    }
}