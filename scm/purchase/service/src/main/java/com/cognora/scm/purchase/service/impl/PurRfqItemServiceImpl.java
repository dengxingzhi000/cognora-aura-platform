package com.cognora.scm.purchase.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cognora.scm.purchase.domain.entity.PurRfqItem;
import com.cognora.scm.purchase.mapper.PurRfqItemMapper;
import com.cognora.scm.purchase.service.IPurRfqItemService;

import java.util.List;

@Slf4j
@Service
public class PurRfqItemServiceImpl extends ServiceImpl<PurRfqItemMapper, PurRfqItem> implements IPurRfqItemService {

    @Override
    @Slave
    public List<PurRfqItem> listByRfqId(String rfqId) {
        return lambdaQuery()
                .eq(PurRfqItem::getRfqId, rfqId)
                .orderByAsc(PurRfqItem::getCreateTime)
                .list();
    }

    @Override
    @Master(reason = "根据询价单ID删除明细")
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByRfqId(String rfqId) {
        return lambdaUpdate()
                .eq(PurRfqItem::getRfqId, rfqId)
                .remove();
    }
}
