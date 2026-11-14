package com.cognora.ecom.ordercenter.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.cognora.ecom.ordercenter.domain.entity.OcOrder;
import com.cognora.ecom.ordercenter.domain.entity.OcOrderItem;

import java.math.BigDecimal;
import java.util.List;

public interface IOrderCenterService extends IService<OcOrder> {

    OcOrder createOrder(OcOrder order, List<OcOrderItem> items);

    OcOrder getOrder(String orderNo);

    Page<OcOrder> pageQuery(int page, int size, String orderNo, String userId, Integer status);

    void cancelOrder(String orderNo, String reason);

    /**
     * Applies a payment that a trusted verifier already confirmed.
     *
     * <p>There is deliberately no {@code payOrder(orderNo, paymentNo)}: a method that only takes
     * an order number and a payment number lets any logged-in caller mark an order paid without
     * the money ever moving, which contradicts the platform rule that the only path to "paid" is
     * a verified payment-channel callback. The caller must be the payment callback handler (see
     * {@code PaymentPaidEventListener}), after {@code ecom-payment} verified the channel
     * signature, the merchant identity and the paid amount.</p>
     *
     * @param orderNo       the order to mark paid
     * @param paymentNo     the payment number that was verified
     * @param paidAmount    the amount the channel actually charged; must match the payable amount
     * @param transactionId the channel transaction id, recorded for reconciliation
     */
    void applyVerifiedPayment(String orderNo, String paymentNo, BigDecimal paidAmount, String transactionId);

    void shipOrder(String orderNo, String logisticsNo);

    void deliverOrder(String orderNo);

    void confirmOrder(String orderNo);
}
