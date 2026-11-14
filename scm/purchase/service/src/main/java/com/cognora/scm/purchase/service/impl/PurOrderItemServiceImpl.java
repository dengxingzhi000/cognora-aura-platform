package com.cognora.scm.purchase.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cognora.scm.purchase.domain.entity.PurOrderItem;
import com.cognora.scm.purchase.mapper.PurOrderItemMapper;
import com.cognora.scm.purchase.service.IPurOrderItemService;

import java.util.List;

@Slf4j
@Service
public class PurOrderItemServiceImpl extends ServiceImpl<PurOrderItemMapper, PurOrderItem> implements IPurOrderItemService {

    @Override
    @Slave
    public List<PurOrderItem> listByOrderId(String orderId) {
        return lambdaQuery()
                .eq(PurOrderItem::getOrderId, orderId)
                .orderByAsc(PurOrderItem::getCreateTime)
                .list();
    }

    @Override
    @Master(reason = "根据订单ID删除明细")
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByOrderId(String orderId) {
        return lambdaUpdate()
                .eq(PurOrderItem::getOrderId, orderId)
                .remove();
    }
}
