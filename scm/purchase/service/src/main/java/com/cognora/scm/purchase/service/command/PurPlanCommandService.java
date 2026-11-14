package com.cognora.scm.purchase.service.command;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.exception.ServiceException;
import com.cognora.scm.purchase.domain.PurPlanStatus;
import com.cognora.scm.purchase.domain.entity.PurPlan;
import com.cognora.scm.purchase.domain.entity.PurPlanItem;
import com.cognora.scm.purchase.mapper.PurPlanItemMapper;
import com.cognora.scm.purchase.mapper.PurPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 采购计划命令服务：草稿维护、审批流转与「补货建议转采购计划」。
 *
 * <p>状态语义与两个前置 bug 的修法见 {@link PurPlanStatus}。要点：
 * <ul>
 *   <li>{@code complete()} 走 {@code EXECUTING(4) → COMPLETED(3)}，不再假借
 *       {@code APPROVED→REJECTED} 校验；</li>
 *   <li>{@code startExecution()} 走 {@code APPROVED(2) → EXECUTING(4)}，不再是
 *       同状态空转。</li>
 * </ul>
 *
 * <p><b>为什么四条流转全部只走进程内状态机、不再调 {@code StatusValidator}：</b>
 * 此前只有 {@code submit/approve} 过运行期状态字典（Dubbo 调 platform-system），
 * 而 {@code startExecution/complete} 只走编译期枚举——同一个状态机一半依赖字典、
 * 一半不依赖，行为不对等：
 * <ul>
 *   <li>platform-system 不可用时，计划<strong>能提交能审批，却无法开始执行/完成</strong>，
 *       故障表现取决于你调的是哪个动作；</li>
 *   <li>字典里的 {@code PURCHASE} 是 {@code DRAFT→PENDING_APPROVAL→APPROVED→ORDERED→…}
 *       的字符串词表，与 {@code pur_plan.status} 的 SMALLINT 编码（0..4）不是一套，
 *       两边一旦漂移就会出现「字典说合法、编码写不进去」。</li>
 * </ul>
 * 现在唯一权威是 {@link PurPlanStatus#canTransitionTo}：纯进程内、无网络依赖、
 * 可单测钉死。运行期字典仍可用于前端展示可选动作，但<strong>不再作为写路径的
 * 校验闸门</strong>。
 *
 * @author SCM Platform Team
 * @since 2025-12-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurPlanCommandService {

    private final PurPlanMapper purPlanMapper;
    private final PurPlanItemMapper purPlanItemMapper;

    @Master(reason = "保存采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean save(PurPlan entity) {
        return purPlanMapper.insert(entity) > 0;
    }

    @Master(reason = "更新采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(PurPlan entity) {
        return purPlanMapper.updateById(entity) > 0;
    }

    @Master(reason = "删除采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(String id) {
        return purPlanMapper.deleteById(id) > 0;
    }

    @Master(reason = "提交采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean submit(String id) {
        PurPlan plan = requirePlan(id);
        transition(plan, PurPlanStatus.PENDING_APPROVAL);
        return purPlanMapper.updateById(plan) > 0;
    }

    @Master(reason = "审批采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean approve(String id, String approverId, String approverName) {
        PurPlan plan = requirePlan(id);
        transition(plan, PurPlanStatus.APPROVED);
        plan.setApprovedBy(approverId);
        plan.setApprovedByName(approverName);
        plan.setApprovedAt(LocalDateTime.now());
        return purPlanMapper.updateById(plan) > 0;
    }

    /**
     * 开始执行：APPROVED(2) → EXECUTING(4)。
     *
     * @throws ServiceException 计划不存在或不在 APPROVED
     */
    @Master(reason = "开始执行采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean startExecution(String id) {
        PurPlan plan = requirePlan(id);
        transition(plan, PurPlanStatus.EXECUTING);
        return purPlanMapper.updateById(plan) > 0;
    }

    /**
     * 完结采购计划：EXECUTING(4) → COMPLETED(3)，执行率置 100%。
     *
     * @throws ServiceException 计划不存在或不在 EXECUTING
     */
    @Master(reason = "完成采购计划")
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(String id) {
        PurPlan plan = requirePlan(id);
        transition(plan, PurPlanStatus.COMPLETED);
        plan.setExecutionRate(BigDecimal.valueOf(100));
        return purPlanMapper.updateById(plan) > 0;
    }

    /**
     * 由补货建议单创建采购计划草稿（DRAFT），并写入明细。
     *
     * <p>调用方是 purchase 侧的 {@code ReplenishmentConvertedConsumer}。幂等键是
     * {@code plan_no}（缺省取建议单号）：重复消费同一事件时命中已存在的计划并
     * <b>原样返回</b>，不再插入第二张。
     *
     * @param planNo     计划号；同时是幂等键
     * @param planName   计划名称；为空时给缺省名
     * @param planPeriod 计划周期；为空时取计划号
     * @param remark     备注
     * @param items      明细（materialCode + plannedQuantity 必填）
     * @return 新建或已存在的计划
     * @throws ServiceException 参数非法
     */
    @Master(reason = "补货建议转采购计划")
    @Transactional(rollbackFor = Exception.class)
    public PurPlan createFromReplenishment(String planNo, String planName, String planPeriod,
                                           String remark, List<PurPlanItem> items) {
        List<PurPlanItem> lines = items == null ? List.of() : items;
        if (lines.isEmpty()) {
            throw badRequest("补货建议没有可转采购计划的明细");
        }
        if (!StringUtils.hasText(planNo)) {
            throw badRequest("采购计划号不能为空");
        }
        String resolvedPlanNo = planNo.trim();

        // 幂等：同号计划已存在则直接返回，保证重复消费只建一张。
        PurPlan existing = findByPlanNo(resolvedPlanNo);
        if (existing != null) {
            log.info("采购计划 {} 已存在，跳过重复创建", resolvedPlanNo);
            return existing;
        }

        PurPlan plan = new PurPlan()
                .setId(UUID.randomUUID().toString())
                .setPlanNo(resolvedPlanNo)
                .setPlanName(StringUtils.hasText(planName) ? planName.trim() : "补货计划 " + resolvedPlanNo)
                .setPlanType(1)
                .setPlanPeriod(StringUtils.hasText(planPeriod) ? planPeriod : resolvedPlanNo)
                .setStatus(PurPlanStatus.DRAFT.getCode())
                .setDeleted(false)
                .setRemark(remark);
        plan.setCreateTime(LocalDateTime.now());
        plan.setUpdateTime(LocalDateTime.now());
        purPlanMapper.insert(plan);

        BigDecimal totalBudget = BigDecimal.ZERO;
        int index = 0;
        for (PurPlanItem line : lines) {
            index++;
            if (line == null || !StringUtils.hasText(line.getMaterialCode())) {
                throw badRequest("第 " + index + " 行明细缺少物料编码（SKU→material 映射缺失）");
            }
            BigDecimal quantity = line.getPlannedQuantity();
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw badRequest("第 " + index + " 行明细的数量必须大于 0");
            }
            BigDecimal budgetPrice = line.getBudgetPrice() == null
                    ? BigDecimal.ZERO : line.getBudgetPrice();
            BigDecimal amount = quantity.multiply(budgetPrice).setScale(2, RoundingMode.HALF_UP);
            totalBudget = totalBudget.add(amount);

            line.setId(UUID.randomUUID().toString());
            line.setPlanId(plan.getId());
            line.setPlanNo(resolvedPlanNo);
            line.setBudgetPrice(budgetPrice);
            line.setBudgetAmount(amount);
            if (line.getPurchasedQuantity() == null) {
                line.setPurchasedQuantity(BigDecimal.ZERO);
            }
            if (!StringUtils.hasText(line.getUnit())) {
                line.setUnit("件");
            }
            line.setCreateTime(LocalDateTime.now());
            purPlanItemMapper.insert(line);
        }

        plan.setTotalBudget(totalBudget);
        purPlanMapper.updateById(plan);

        log.info("补货建议已生成采购计划 {}: items={}, budget={}",
                resolvedPlanNo, lines.size(), totalBudget);
        return plan;
    }

    /**
     * 按计划号查未删除的计划，供消费侧幂等判定。
     */
    @Master(reason = "消费侧幂等校验需读主库")
    public PurPlan findByPlanNo(String planNo) {
        if (!StringUtils.hasText(planNo)) {
            return null;
        }
        return purPlanMapper.selectOne(Wrappers.<PurPlan>lambdaQuery()
                .eq(PurPlan::getPlanNo, planNo)
                .eq(PurPlan::getDeleted, false)
                .last("LIMIT 1"));
    }

    // ---------------------------------------------------------------- helpers

    private PurPlan requirePlan(String id) {
        PurPlan plan = purPlanMapper.selectById(id);
        if (plan == null || Boolean.TRUE.equals(plan.getDeleted())) {
            throw new ServiceException(404, HttpStatus.NOT_FOUND, "采购计划不存在: " + id);
        }
        return plan;
    }

    private PurPlanStatus requireStatus(PurPlan plan) {
        PurPlanStatus status = PurPlanStatus.fromCode(plan.getStatus());
        if (status == null) {
            throw conflict("采购计划状态非法: " + plan.getStatus());
        }
        return status;
    }

    /** 走状态机校验后再写状态，避免绕过 {@link PurPlanStatus#canTransitionTo}。 */
    private void transition(PurPlan plan, PurPlanStatus target) {
        PurPlanStatus current = requireStatus(plan);
        if (!current.canTransitionTo(target)) {
            throw conflict("非法的采购计划状态流转: " + current + " → " + target);
        }
        plan.setStatus(target.getCode());
        plan.setUpdateTime(LocalDateTime.now());
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(400, HttpStatus.BAD_REQUEST, message);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(409, HttpStatus.CONFLICT, message);
    }
}
