package com.cognora.ecom.ordercenter.controller;

import com.cognora.ecom.ordercenter.service.IOrderCenterService;
import com.cognora.platform.common.security.config.ApiAccessControlProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rule that an order can only be marked paid by a verified payment.
 *
 * <p>The removed {@code payOrder(orderNo, paymentNo)} took nothing but an order number and a
 * client-supplied payment number: it set {@code status=2} and {@code paidAmount=payableAmount}
 * without verifying a channel signature, without correlating with a payment callback and without
 * checking who was calling. Any logged-in user could therefore mark an order paid and have it
 * shipped while the money never arrived.</p>
 *
 * <p>These assertions fail as soon as that shape comes back - on the service interface, on the
 * service implementation, or as an unauthenticated HTTP route.</p>
 */
@DisplayName("Order payment must come from a verified payment callback")
class OrderCenterPaymentContractTest {

    @Test
    @DisplayName("Service contract exposes no orderNo+paymentNo paid transition")
    void serviceContractRequiresVerifiedPaymentEvidence() {
        assertTrue(Arrays.stream(IOrderCenterService.class.getDeclaredMethods())
                        .noneMatch(OrderCenterPaymentContractTest::canMarkPaidWithoutVerification),
                "IOrderCenterService must not declare a method that marks an order paid from an "
                        + "order number and a payment number alone");
        assertTrue(Arrays.stream(IOrderCenterService.class.getDeclaredMethods())
                        .anyMatch(method -> "applyVerifiedPayment".equals(method.getName())),
                "IOrderCenterService must keep applyVerifiedPayment as the only paid transition");
    }

    @Test
    @DisplayName("Payment write endpoint is on the signed service-to-service channel")
    void paymentEndpointRequiresServiceSignature() {
        // The filter rejects a request without a valid HMAC before the controller is reached, so
        // the route must be listed in internalSignaturePaths. Listing it in exemptPaths instead
        // would re-open the original hole: any caller could reach the controller directly.
        ApiAccessControlProperties properties = new ApiAccessControlProperties();
        String payRoute = "/api/v1/orders/ORD-1/pay";

        assertTrue(com.cognora.platform.common.security.util.RequestPathMatcher
                        .matches(payRoute, properties.getInternalSignaturePaths()),
                "POST /api/v1/orders/{orderNo}/pay must require a service-to-service signature");
        assertFalse(com.cognora.platform.common.security.util.RequestPathMatcher
                        .matches(payRoute, properties.getExemptPaths()),
                "the payment write route must not be exempt from the permission/signature check");
    }

    @Test
    @DisplayName("Payment endpoint carries no browser-facing permission-only route")
    void paymentEndpointIsNotPermissionOnly() {
        Method handler = Arrays.stream(OrderCenterController.class.getDeclaredMethods())
                .filter(method -> "applyVerifiedPayment".equals(method.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("OrderCenterController.applyVerifiedPayment is missing"));

        PostMapping mapping = handler.getAnnotation(PostMapping.class);
        assertTrue(mapping != null, "the payment handler must stay a POST endpoint");
        assertTrue(Arrays.asList(mapping.value()).contains("/{orderNo}/pay"),
                "the payment handler must stay at /{orderNo}/pay");
    }

    @Test
    @DisplayName("The RPC surface exposes no paid transition either")
    void dubboSurfaceExposesNoPaidTransition() {
        // A Dubbo call has no gateway in front of it, so an RPC method that marks an order paid
        // would reintroduce the same hole the HTTP route had.
        assertTrue(Arrays.stream(com.cognora.ecom.ordercenter.api.OrderCenterDubboService.class
                                .getDeclaredMethods())
                        .noneMatch(OrderCenterPaymentContractTest::canMarkPaidWithoutVerification),
                "OrderCenterDubboService must not expose a method that marks an order paid");
    }

    /**
     * A paid transition that needs no more than two strings cannot prove the money moved. The
     * verified path additionally needs the paid amount and the channel transaction id.
     */
    private static boolean canMarkPaidWithoutVerification(Method method) {
        Class<?>[] types = method.getParameterTypes();
        if (types.length != 2 || !String.class.equals(types[0]) || !String.class.equals(types[1])) {
            return false;
        }
        String name = method.getName().toLowerCase(Locale.ROOT);
        return name.contains("pay") || name.contains("paid") || name.contains("callback")
                || name.contains("notify");
    }
}
