package com.cognora.ecom.payment.service.notify;

import com.cognora.platform.common.constant.HttpHeaderConstants;
import com.cognora.platform.common.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class SignedOrderPaidNotifierTest {

    private static final String ORDER_PAID_URL =
            "http://order-center/api/v1/orders/ORD-1/pay";

    private MockRestServiceServer server;
    private SignedOrderPaidNotifier notifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        notifier = new SignedOrderPaidNotifier(builder, true, "http://order-center");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void sendsTenantHeaderAndPaymentEvidence() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
        server.expect(requestTo(ORDER_PAID_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaderConstants.TENANT_ID, tenantId.toString()))
                .andExpect(content().json("""
                        {
                          "paymentNo": "PAY-1",
                          "paidAmount": 10.00,
                          "transactionId": "TXN-1"
                        }
                        """))
                .andRespond(withNoContent());

        assertTrue(notifier.notifyOrderPaid(
                "ORD-1", "PAY-1", new BigDecimal("10.00"), "TXN-1"));
        server.verify();
    }

    @Test
    void omitsExplicitTenantHeaderWhenContextIsMissing() {
        server.expect(requestTo(ORDER_PAID_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(HttpHeaderConstants.TENANT_ID))
                .andRespond(withNoContent());

        assertTrue(notifier.notifyOrderPaid(
                "ORD-1", "PAY-1", new BigDecimal("10.00"), "TXN-1"));
        server.verify();
    }

    @Test
    void returnsFalseWhenNotificationIsDisabled() {
        SignedOrderPaidNotifier disabled =
                new SignedOrderPaidNotifier(RestClient.builder(), false, "http://order-center");

        assertFalse(disabled.notifyOrderPaid(
                "ORD-1", "PAY-1", new BigDecimal("10.00"), "TXN-1"));
        server.verify();
    }

    @Test
    void returnsFalseWhenOrderNumberIsBlank() {
        assertFalse(notifier.notifyOrderPaid(
                " ", "PAY-1", new BigDecimal("10.00"), "TXN-1"));
        server.verify();
    }

    @Test
    void returnsFalseWhenOrderCenterRejectsTheCall() {
        server.expect(requestTo(ORDER_PAID_URL))
                .andRespond(withServerError());

        assertFalse(notifier.notifyOrderPaid(
                "ORD-1", "PAY-1", new BigDecimal("10.00"), "TXN-1"));
        server.verify();
    }
}
