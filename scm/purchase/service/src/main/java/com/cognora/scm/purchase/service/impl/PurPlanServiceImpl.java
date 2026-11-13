package com.cognora.scm.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.scm.purchase.domain.entity.PurPlan;
import com.cognora.scm.purchase.mapper.PurPlanMapper;
import com.cognora.scm.purchase.service.IPurPlanService;
import com.cognora.scm.purchase.service.command.PurPlanCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 采购计划读写服务。
 *
 * <p>写操作（submit/approve/startExecution/complete）<b>委托</b>给
 * {@link PurPlanCommandService}，不再各自持有一份状态流转副本。此前本类与
 * command 服务各写了一遍 {@code complete()}/{@code startExecution()}，
 * 两份都带着同样的 bug（完成写成驳回、执行是自转空操作），修一份会漏另一份。
 */
@Slf4j
@Service
public class PurPlanServiceImpl extends ServiceImpl<PurPlanMapper, PurPlan> implements IPurPlanService {

    @Autowired
    private PurPlanCommandService purPlanCommandService;

    @Override
    @Slave
    public PurPlan getByPlanNo(String planNo) {
        return lambdaQuery()
                .eq(PurPlan::getPlanNo, planNo)
                .eq(PurPlan::getDeleted, false)
                .one();
    }

    @Override
    @Slave
    public Page<PurPlan> pageQuery(int page, int size, Integer status, Integer planType, String keyword) {
        LambdaQueryWrapper<PurPlan> wrapper = Wrappers.lambdaQuery();
        if (status != null) {
            wrapper.eq(PurPlan::getStatus, status);
        }
        if (planType != null) {
            wrapper.eq(PurPlan::getPlanType, planType);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(PurPlan::getPlanNo, keyword)
                    .or()
                    .like(PurPlan::getPlanName, keyword));
        }
        wrapper.eq(PurPlan::getDeleted, false);
        wrapper.orderByDesc(PurPlan::getCreateTime);
        return page(new Page<>(page, size), wrapper);
    }

    @Override
    @Slave
    public List<PurPlan> listByStatus(Integer status) {
        return lambdaQuery()
                .eq(PurPlan::getStatus, status)
                .eq(PurPlan::getDeleted, false)
                .orderByDesc(PurPlan::getCreateTime)
                .list();
    }

    @Override
    @Master(reason = "提交采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean submit(String id) {
        return purPlanCommandService.submit(id);
    }

    @Override
    @Master(reason = "审批采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean approve(String id, String approverId, String approverName) {
        return purPlanCommandService.approve(id, approverId, approverName);
    }

    @Override
    @Master(reason = "开始执行采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean startExecution(String id) {
        return purPlanCommandService.startExecution(id);
    }

    @Override
    @Master(reason = "完成采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(String id) {
        return purPlanCommandService.complete(id);
    }
}
