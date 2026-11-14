package com.cognora.scm.purchase.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cognora.scm.purchase.domain.entity.PurPlanItem;
import com.cognora.scm.purchase.mapper.PurPlanItemMapper;
import com.cognora.scm.purchase.service.IPurPlanItemService;

import java.util.List;

@Slf4j
@Service
public class PurPlanItemServiceImpl extends ServiceImpl<PurPlanItemMapper, PurPlanItem> implements IPurPlanItemService {

    @Override
    @Slave
    public List<PurPlanItem> listByPlanId(String planId) {
        return lambdaQuery()
                .eq(PurPlanItem::getPlanId, planId)
                .orderByAsc(PurPlanItem::getCreateTime)
                .list();
    }

    @Override
    @Master(reason = "根据计划ID删除明细")
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByPlanId(String planId) {
        return lambdaUpdate()
                .eq(PurPlanItem::getPlanId, planId)
                .remove();
    }
}
