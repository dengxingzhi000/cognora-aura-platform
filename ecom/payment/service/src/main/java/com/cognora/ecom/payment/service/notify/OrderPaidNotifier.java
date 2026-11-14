package com.cognora.ecom.payment.service.notify;

import java.math.BigDecimal;

/**
 * 支付成功后向订单侧回写「已支付」的通道。
 *
 * <p>只有 {@code PaymentOrderServiceImpl} 在**渠道验签通过并落库成功之后**才会调用它，
 * 因此这是「已验证的支付」流向订单的唯一出口。实现必须走服务间 HMAC 签名通道
 * （order-center 的 {@code POST /api/v1/orders/{orderNo}/pay} 只认签名），
 * 不能把这条通知做成一个公开的 HTTP 端点或未签名的 RPC。</p>
 *
 * <p>失败语义：实现**不得抛出**业务异常打断支付流程——钱已经到账、支付单已置 paid，
 * 订单状态没推进是可重试的后续问题，不是支付失败。实现应记录 ERROR 并返回 {@code false}，
 * 由对账/重试任务补偿。</p>
 */
public interface OrderPaidNotifier {

    /**
     * @param orderNo       订单号
     * @param paymentNo     支付单号
     * @param paidAmount    渠道实付金额（与支付单金额已校验相等）
     * @param transactionId 渠道交易号
     * @return 通知是否送达并被订单侧接受
     */
    boolean notifyOrderPaid(String orderNo, String paymentNo, BigDecimal paidAmount, String transactionId);
}
