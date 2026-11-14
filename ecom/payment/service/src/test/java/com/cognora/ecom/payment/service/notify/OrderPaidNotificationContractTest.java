package com.cognora.ecom.payment.service.notify;

import com.cognora.platform.common.security.config.ApiAccessControlProperties;
import com.cognora.platform.common.security.util.RequestPathMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two halves of the "payment settles the order" link.
 *
 * <p>The notifier calls order-center's {@code POST /api/v1/orders/{orderNo}/pay}. That endpoint
 * is only safe because order-center requires a service-to-service HMAC signature on it; if the
 * route ever moved to {@code exemptPaths} (or the notifier stopped using the signing client), the
 * original P0-1 hole would be back: anyone could mark an order paid without money moving.</p>
 *
 * <p>The mirror test in {@code ecom-order-center-service}
 * ({@code OrderCenterPaymentContractTest}) asserts the same route from the other side.</p>
 */
@DisplayName("Payment → order notification targets the signed internal route")
class OrderPaidNotificationContractTest {

    /** Must match order-center's controller mapping exactly. */
    private static final String ORDER_PAID_ROUTE = "/api/v1/orders/ORD-1/pay";

    @Test
    @DisplayName("The order-paid route requires a service signature")
    void orderPaidRouteRequiresServiceSignature() {
        ApiAccessControlProperties properties = new ApiAccessControlProperties();

        assertTrue(RequestPathMatcher.matches(ORDER_PAID_ROUTE, properties.getInternalSignaturePaths()),
                "the notifier's target must be an internal-signature route, or order-center would "
                        + "reject the signed call - and worse, an unsigned call would be accepted");
        assertFalse(RequestPathMatcher.matches(ORDER_PAID_ROUTE, properties.getExemptPaths()),
                "the route must not be exempt: exemptPaths means no signature and no permission");
    }

    /**
     * The event must carry the amount the channel charged. Without it the notifier cannot satisfy
     * order-center's {@code paidAmount == payableAmount} check, and a "paid" order could be
     * advanced by a payment that settled something else.
     */
    @Test
    @DisplayName("The paid event carries orderNo, paymentNo, paidAmount and transactionId")
    void paidEventCarriesVerificationEvidence() {
        PaymentPaidEvent event = new PaymentPaidEvent("ORD-1", "PAY-1",
                new java.math.BigDecimal("10.00"), "TXN-1");

        assertFalse(event.orderNo() == null || event.orderNo().isBlank());
        assertFalse(event.paymentNo() == null || event.paymentNo().isBlank());
        assertTrue(event.paidAmount() != null && event.paidAmount().signum() > 0,
                "paidAmount is what proves the money moved; it must not be null or zero");
        assertFalse(event.transactionId() == null || event.transactionId().isBlank(),
                "order-center rejects a paid transition without a channel transaction id");
    }
}
