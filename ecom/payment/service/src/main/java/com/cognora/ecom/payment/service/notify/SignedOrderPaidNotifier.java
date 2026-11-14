package com.cognora.ecom.payment.service.notify;

import com.cognora.platform.common.security.util.AuthTokenContext;
import com.cognora.platform.common.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通过服务间 HMAC 签名通道回调 order-center 的「订单已支付」端点。
 *
 * <p>用的是 {@code platform/common/web} 提供的 {@code @LoadBalanced RestClient.Builder}，
 * 它上面挂了 {@code RestClientRequestSignatureInterceptor}（加 X-Timestamp/X-Nonce/X-Signature/
 * X-App-Id）以及租户/令牌透传拦截器。因此 order-center 侧
 * {@code InternalEndpointSignatureFilter} 会把这条请求判为已验签的内部调用。</p>
 *
 * <p><strong>绝不能</strong>改成一个不带签名的普通客户端：order-center 的该路由在
 * {@code internalSignaturePaths} 里，只认签名；去掉签名等于把「谁都能把订单置为已付」
 * 的洞重新打开（那正是 P0-1）。</p>
 */
@Slf4j
@Component
public class SignedOrderPaidNotifier implements OrderPaidNotifier {

    /** order-center 在 Nacos 中的注册名。 */
    private static final String ORDER_CENTER_SERVICE = "http://ecom-order-center";

    private final RestClient restClient;
    private final boolean enabled;

    public SignedOrderPaidNotifier(RestClient.Builder loadBalancedRestClientBuilder,
                                   @Value("${cognora.payment.order-notify.enabled:true}") boolean enabled,
                                   @Value("${cognora.payment.order-notify.base-url:" + ORDER_CENTER_SERVICE + "}")
                                   String baseUrl) {
        this.enabled = enabled;
        this.restClient = loadBalancedRestClientBuilder.clone().baseUrl(baseUrl).build();
    }

    @Override
    public boolean notifyOrderPaid(String orderNo, String paymentNo,
                                   BigDecimal paidAmount, String transactionId) {
        if (!enabled) {
            log.warn("ORDER_PAID_NOT_DELIVERED orderNo={}, paymentNo={} - "
                            + "cognora.payment.order-notify.enabled=false, so the order is not advanced",
                    safe(orderNo), safe(paymentNo));
            return false;
        }
        if (orderNo == null || orderNo.isBlank()) {
            log.warn("Skipping order-paid notification: the payment has no order number");
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentNo", paymentNo);
        body.put("paidAmount", paidAmount);
        body.put("transactionId", transactionId);

        try {
            restClient.post()
                    .uri("/api/v1/orders/{orderNo}/pay", orderNo)
                    .contentType(MediaType.APPLICATION_JSON)
                    // The tenant header matters: order-center's TenantInterceptor filters oc_order
                    // by tenant_id, so a callback without it would not find the order. The shared
                    // builder forwards it from the context, but the AFTER_COMMIT listener runs on
                    // another thread only when async - keep the explicit binding for clarity.
                    .headers(headers -> tenantHeader().ifPresent(t -> headers.set("X-Tenant-Id", t)))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Order advanced to paid: orderNo={}, paymentNo={}", safe(orderNo), safe(paymentNo));
            return true;
        } catch (RuntimeException ex) {
            log.error("Order-paid notification failed: orderNo={}, paymentNo={}: {}",
                    safe(orderNo), safe(paymentNo), safe(ex.getMessage()));
            return false;
        }
    }

    /**
     * The tenant of the payment that was settled, forwarded so order-center's SQL isolation
     * finds the same order. {@code TenantContextHolder} is a ThreadLocal, so it is read here
     * rather than captured earlier: the listener may run on the callback thread.
     */
    private static java.util.Optional<String> tenantHeader() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null) {
            return java.util.Optional.of(tenantId.toString());
        }
        // No thread-bound tenant (defensive): let the shared RestClient interceptor decide.
        return java.util.Optional.empty();
    }

    private static String safe(String value) {
        return (value + "").replace("\r", "_").replace("\n", "_");
    }
}
