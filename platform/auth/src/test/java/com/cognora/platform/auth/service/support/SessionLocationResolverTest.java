package com.cognora.platform.auth.service.support;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionLocationResolverTest {

    @Test
    void labelsPrivateAddressesAsLocalNetwork() {
        assertEquals("本地网络", SessionLocationResolver.resolve("10.1.2.3", "Asia/Shanghai"));
        assertEquals("本地网络", SessionLocationResolver.resolve("192.168.1.9", null));
        assertEquals("本地网络", SessionLocationResolver.resolve("172.20.1.5", null));
        assertEquals("本地网络", SessionLocationResolver.resolve("127.0.0.1", null));
        assertEquals("本地网络", SessionLocationResolver.resolve("::1", null));
    }

    @Test
    void usesTheClientTimezoneForPublicAddresses() {
        String expected = ZoneId.of("Asia/Shanghai")
                .getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE);

        assertEquals(expected, SessionLocationResolver.resolve("203.0.113.10", "Asia/Shanghai"));
    }

    @Test
    void keepsUnknownTimezoneReadable() {
        assertEquals("Not/AZone", SessionLocationResolver.resolve("203.0.113.10", "Not/AZone"));
    }

    @Test
    void returnsNullWithoutIpOrTimezone() {
        assertNull(SessionLocationResolver.resolve("203.0.113.10", null));
        assertNull(SessionLocationResolver.resolve("203.0.113.10", " "));
    }
}