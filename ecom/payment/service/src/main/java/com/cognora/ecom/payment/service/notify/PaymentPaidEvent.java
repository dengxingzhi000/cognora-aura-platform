package com.cognora.ecom.payment.service.notify;

import java.math.BigDecimal;

/**
 * 一条「支付已验签并落库」的领域事实。
 *
 * <p>由 {@code PaymentOrderServiceImpl} 在支付单置为 paid 的**同一个事务里**发布，
 * 由 {@link PaymentPaidListener} 在事务**提交之后**异步转成对 order-center 的签名回调。
 * 用事件而不是在事务里直接 HTTP 调用，是为了避免：① 在本地事务未提交时通知成功、
 * 提交却回滚（订单变成已付、支付单却没有）；② 远程调用的耗时和失败拖长、拖垮支付回调事务。</p>
 */
public record PaymentPaidEvent(String orderNo, String paymentNo,
                               BigDecimal paidAmount, String transactionId) {
}
