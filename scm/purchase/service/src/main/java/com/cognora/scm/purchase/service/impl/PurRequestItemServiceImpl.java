package com.cognora.scm.purchase.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cognora.scm.purchase.domain.entity.PurRequestItem;
import com.cognora.scm.purchase.mapper.PurRequestItemMapper;
import com.cognora.scm.purchase.service.IPurRequestItemService;

import java.util.List;

@Slf4j
@Service
public class PurRequestItemServiceImpl extends ServiceImpl<PurRequestItemMapper, PurRequestItem> implements IPurRequestItemService {

    @Override
    @Slave
    public List<PurRequestItem> listByRequestId(String requestId) {
        return lambdaQuery()
                .eq(PurRequestItem::getRequestId, requestId)
                .orderByAsc(PurRequestItem::getCreateTime)
                .list();
    }

    @Override
    @Master(reason = "根据申请ID删除明细")
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByRequestId(String requestId) {
        return lambdaUpdate()
                .eq(PurRequestItem::getRequestId, requestId)
                .remove();
    }
}
