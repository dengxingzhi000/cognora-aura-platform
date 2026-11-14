package com.cognora.scm.purchase.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cognora.scm.purchase.domain.entity.PurQuotationItem;
import com.cognora.scm.purchase.mapper.PurQuotationItemMapper;
import com.cognora.scm.purchase.service.IPurQuotationItemService;

import java.util.List;

@Slf4j
@Service
public class PurQuotationItemServiceImpl extends ServiceImpl<PurQuotationItemMapper, PurQuotationItem> implements IPurQuotationItemService {

    @Override
    @Slave
    public List<PurQuotationItem> listByQuotationId(String quotationId) {
        return lambdaQuery()
                .eq(PurQuotationItem::getQuotationId, quotationId)
                .orderByAsc(PurQuotationItem::getCreateTime)
                .list();
    }

    @Override
    @Master(reason = "根据报价单ID删除明细")
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByQuotationId(String quotationId) {
        return lambdaUpdate()
                .eq(PurQuotationItem::getQuotationId, quotationId)
                .remove();
    }
}
