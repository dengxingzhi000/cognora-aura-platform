package com.cognora.scm.purchase.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cognora.scm.purchase.domain.entity.PurReceiptItem;
import com.cognora.scm.purchase.mapper.PurReceiptItemMapper;
import com.cognora.scm.purchase.service.IPurReceiptItemService;

import java.util.List;

@Slf4j
@Service
public class PurReceiptItemServiceImpl extends ServiceImpl<PurReceiptItemMapper, PurReceiptItem> implements IPurReceiptItemService {

    @Override
    @Slave
    public List<PurReceiptItem> listByReceiptId(String receiptId) {
        return lambdaQuery()
                .eq(PurReceiptItem::getReceiptId, receiptId)
                .orderByAsc(PurReceiptItem::getCreateTime)
                .list();
    }

    @Override
    @Master(reason = "根据入库单ID删除明细")
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByReceiptId(String receiptId) {
        return lambdaUpdate()
                .eq(PurReceiptItem::getReceiptId, receiptId)
                .remove();
    }
}
