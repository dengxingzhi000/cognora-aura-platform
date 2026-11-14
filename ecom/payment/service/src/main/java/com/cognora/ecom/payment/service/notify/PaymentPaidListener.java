package com.cognora.ecom.payment.service.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把「支付已验签并落库」转成对 order-center 的一次签名回调。
 *
 * <p><strong>为什么是 {@code AFTER_COMMIT}：</strong>在支付单事务提交之前通知订单，
 * 一旦支付事务回滚，就会出现「订单已付但支付单未落库」的不一致；提交之后通知则最坏是
 * 「支付单已付、订单暂时未推进」，这是可重试、可对账的方向。</p>
 *
 * <p><strong>为什么吞掉异常：</strong>钱已经到账、支付单已置 paid。此时把异常抛给渠道回调，
 * 渠道会重试回调，而 {@code applyPaid} 对重复回调是幂等的 no-op —— 于是订单永远不会被推进。
 * 正确做法是记录 ERROR + 指标，由重试/对账补偿，同时向渠道返回成功以免它无限重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentPaidListener {

    private final OrderPaidNotifier orderPaidNotifier;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentPaid(PaymentPaidEvent event) {
        try {
            boolean delivered = orderPaidNotifier.notifyOrderPaid(
                    event.orderNo(), event.paymentNo(), event.paidAmount(), event.transactionId());
            if (!delivered) {
                // The notifier already logged the cause; this line marks the reconciliation queue
                // entry so an operator can find unpaid-but-settled orders by searching this marker.
                log.error("ORDER_PAID_NOT_DELIVERED orderNo={}, paymentNo={}, transactionId={} "
                                + "- the payment is settled but the order was not advanced; "
                                + "reconcile and replay this notification",
                        safe(event.orderNo()), safe(event.paymentNo()), safe(event.transactionId()));
            }
        } catch (RuntimeException ex) {
            log.error("ORDER_PAID_NOT_DELIVERED orderNo={}, paymentNo={} - notification threw; "
                            + "the payment stays settled, the order must be reconciled",
                    safe(event.orderNo()), safe(event.paymentNo()), ex);
        }
    }

    private static String safe(String value) {
        return (value + "").replace("\r", "_").replace("\n", "_");
    }
}
