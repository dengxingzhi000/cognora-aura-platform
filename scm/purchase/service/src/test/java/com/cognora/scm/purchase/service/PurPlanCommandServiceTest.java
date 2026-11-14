package com.cognora.scm.purchase.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cognora.platform.common.exception.ServiceException;
import com.cognora.scm.purchase.domain.PurPlanStatus;
import com.cognora.scm.purchase.domain.entity.PurPlan;
import com.cognora.scm.purchase.domain.entity.PurPlanItem;
import com.cognora.scm.purchase.mapper.PurPlanItemMapper;
import com.cognora.scm.purchase.mapper.PurPlanMapper;
import com.cognora.scm.purchase.service.command.PurPlanCommandService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购计划命令服务用例：两个前置 bug 的回归断言 + 由补货建议单建计划的幂等。
 */
class PurPlanCommandServiceTest {

    private final PurPlanMapper planMapper = mock(PurPlanMapper.class);
    private final PurPlanItemMapper itemMapper = mock(PurPlanItemMapper.class);
    private final PurPlanCommandService service =
            new PurPlanCommandService(planMapper, itemMapper);

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PurPlan.class);
        TableInfoHelper.initTableInfo(assistant, PurPlanItem.class);
    }

    private static PurPlan plan(PurPlanStatus status) {
        PurPlan plan = new PurPlan();
        plan.setId("plan-1");
        plan.setPlanNo("PP-20261110-0001");
        plan.setStatus(status.getCode());
        plan.setDeleted(false);
        return plan;
    }

    // ------------------------------------------------------------ bug 修复回归

    @Test
    @DisplayName("bug 修复：complete 把计划置为「已完成」，而不是「已驳回」")
    void completeMarksThePlanCompletedNotRejected() {
        PurPlan executing = plan(PurPlanStatus.EXECUTING);
        when(planMapper.selectById("plan-1")).thenReturn(executing);
        when(planMapper.updateById(any(PurPlan.class))).thenReturn(1);

        assertThat(service.complete("plan-1")).isTrue();

        ArgumentCaptor<PurPlan> captor = ArgumentCaptor.forClass(PurPlan.class);
        verify(planMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PurPlanStatus.COMPLETED.getCode());
        assertThat(PurPlanStatus.fromCode(captor.getValue().getStatus()))
                .isEqualTo(PurPlanStatus.COMPLETED);
        assertThat(captor.getValue().getExecutionRate()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("bug 修复：startExecution 真正把计划推进到「执行中」")
    void startExecutionMovesThePlanToExecuting() {
        PurPlan approved = plan(PurPlanStatus.APPROVED);
        when(planMapper.selectById("plan-1")).thenReturn(approved);
        when(planMapper.updateById(any(PurPlan.class))).thenReturn(1);

        assertThat(service.startExecution("plan-1")).isTrue();

        ArgumentCaptor<PurPlan> captor = ArgumentCaptor.forClass(PurPlan.class);
        verify(planMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PurPlanStatus.EXECUTING.getCode());
        assertThat(captor.getValue().getUpdateTime()).isNotNull();
    }

    @Test
    @DisplayName("已审批的计划不能直接完成，必须先开始执行")
    void completeRejectsAnApprovedPlan() {
        when(planMapper.selectById("plan-1")).thenReturn(plan(PurPlanStatus.APPROVED));

        assertThatThrownBy(() -> service.complete("plan-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非法的采购计划状态流转");

        verify(planMapper, never()).updateById(any(PurPlan.class));
    }

    @Test
    @DisplayName("已完成的计划是终态，不能再次流转")
    void terminalPlanIsRejected() {
        when(planMapper.selectById("plan-1")).thenReturn(plan(PurPlanStatus.COMPLETED));

        assertThatThrownBy(() -> service.startExecution("plan-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非法的采购计划状态流转");
    }

    @Test
    @DisplayName("四条流转都不依赖运行期状态字典：构造器无 StatusValidator，单测可直接跑通")
    void allTransitionsWorkWithoutStatusDictionary() {
        // 回归守卫：此前 submit/approve 会调 StatusValidator（Dubbo → platform-system），
        // 而 startExecution/complete 不调。结果是 platform-system 不可用时
        // 「能审批却无法开始执行」。现在四条流转统一走进程内 PurPlanStatus。
        when(planMapper.selectById("plan-1"))
                .thenReturn(plan(PurPlanStatus.DRAFT))            // submit
                .thenReturn(plan(PurPlanStatus.PENDING_APPROVAL)) // approve
                .thenReturn(plan(PurPlanStatus.APPROVED))         // startExecution
                .thenReturn(plan(PurPlanStatus.EXECUTING));       // complete
        when(planMapper.updateById(any(PurPlan.class))).thenReturn(1);

        assertThat(service.submit("plan-1")).isTrue();
        assertThat(service.approve("plan-1", "user-8", "李审批")).isTrue();
        assertThat(service.startExecution("plan-1")).isTrue();
        assertThat(service.complete("plan-1")).isTrue();

        ArgumentCaptor<PurPlan> captor = ArgumentCaptor.forClass(PurPlan.class);
        verify(planMapper, times(4)).updateById(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(PurPlan::getStatus)
                .containsExactly(
                        PurPlanStatus.PENDING_APPROVAL.getCode(),
                        PurPlanStatus.APPROVED.getCode(),
                        PurPlanStatus.EXECUTING.getCode(),
                        PurPlanStatus.COMPLETED.getCode());
    }

    @Test
    @DisplayName("提交与审批走同一条状态机")
    void submitAndApproveUseTheStateMachine() {
        when(planMapper.selectById("plan-1"))
                .thenReturn(plan(PurPlanStatus.DRAFT))
                .thenReturn(plan(PurPlanStatus.PENDING_APPROVAL));
        when(planMapper.updateById(any(PurPlan.class))).thenReturn(1);

        assertThat(service.submit("plan-1")).isTrue();
        assertThat(service.approve("plan-1", "user-8", "李审批")).isTrue();

        ArgumentCaptor<PurPlan> captor = ArgumentCaptor.forClass(PurPlan.class);
        verify(planMapper, times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues().get(0).getStatus())
                .isEqualTo(PurPlanStatus.PENDING_APPROVAL.getCode());
        assertThat(captor.getAllValues().get(1).getStatus())
                .isEqualTo(PurPlanStatus.APPROVED.getCode());
        assertThat(captor.getAllValues().get(1).getApprovedByName()).isEqualTo("李审批");
    }

    @Test
    @DisplayName("计划不存在时报 404 语义，而不是 NPE")
    void missingPlanIsReported() {
        when(planMapper.selectById("plan-x")).thenReturn(null);

        assertThatThrownBy(() -> service.startExecution("plan-x"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("采购计划不存在");
    }

    // ------------------------------------------------- createFromReplenishment

    @Test
    @DisplayName("由补货建议单建计划：DRAFT + 明细挂到计划头")
    void createFromReplenishmentBuildsADraftWithItems() {
        when(planMapper.selectOne(any())).thenReturn(null);
        when(planMapper.insert(any(PurPlan.class))).thenReturn(1);
        when(itemMapper.insert(any(PurPlanItem.class))).thenReturn(1);

        PurPlanItem item = new PurPlanItem();
        item.setMaterialCode("IP15P-128");
        item.setMaterialName("iPhone 15 Pro 128G");
        item.setPlannedQuantity(new BigDecimal("120"));
        item.setUnit("台");

        PurPlan created = service.createFromReplenishment("RP-RP20261110-000001", "补货计划",
                "2026-W45", "来源建议单 RP20261110-000001", List.of(item));

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getPlanNo()).isEqualTo("RP-RP20261110-000001");
        assertThat(created.getStatus()).isEqualTo(PurPlanStatus.DRAFT.getCode());
        assertThat(created.getPlanPeriod()).isEqualTo("2026-W45");
        assertThat(created.getDeleted()).isFalse();
        verify(planMapper).insert(created);

        ArgumentCaptor<PurPlanItem> captor = ArgumentCaptor.forClass(PurPlanItem.class);
        verify(itemMapper).insert(captor.capture());
        PurPlanItem inserted = captor.getValue();
        assertThat(inserted.getPlanId()).isEqualTo(created.getId());
        assertThat(inserted.getPlanNo()).isEqualTo(created.getPlanNo());
        assertThat(inserted.getId()).isNotBlank();
        assertThat(inserted.getPlannedQuantity()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("重复消费同一建议单事件只建一张计划（幂等）")
    void createFromReplenishmentIsIdempotent() {
        PurPlan existing = plan(PurPlanStatus.DRAFT);
        existing.setPlanNo("RP-RP20261110-000001");
        when(planMapper.selectOne(any())).thenReturn(existing);

        PurPlan result = service.createFromReplenishment("RP-RP20261110-000001", "补货计划",
                "2026-W45", null, List.of(new PurPlanItem()));

        assertThat(result).isSameAs(existing);
        verify(planMapper, never()).insert(any(PurPlan.class));
        verify(itemMapper, never()).insert(any(PurPlanItem.class));
    }

    @Test
    @DisplayName("没有明细时拒绝建计划，避免产生空计划单")
    void createFromReplenishmentRejectsEmptyItems() {
        assertThatThrownBy(() -> service.createFromReplenishment("RP-1", "补货计划", "2026-W45",
                null, List.of()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("没有可转采购计划的明细");

        verify(planMapper, never()).insert(any(PurPlan.class));
        verify(planMapper, never()).selectOne(any());
        verify(itemMapper, never()).insert(any(PurPlanItem.class));
    }

    @Test
    @DisplayName("缺失的计划名称有缺省值，不会写入空名称")
    void createFromReplenishmentDefaultsTheName() {
        when(planMapper.selectOne(any())).thenReturn(null);
        when(planMapper.insert(any(PurPlan.class))).thenReturn(1);
        when(itemMapper.insert(any(PurPlanItem.class))).thenReturn(1);
        PurPlanItem item = new PurPlanItem();
        item.setMaterialCode("M-1");
        item.setPlannedQuantity(BigDecimal.ONE);
        item.setUnit("件");

        PurPlan created = service.createFromReplenishment("RP-2", "  ", "2026-W45", null,
                List.of(item));

        assertThat(created.getPlanName()).contains("RP-2");
        verify(planMapper).insert(any(PurPlan.class));
        verify(planMapper).selectOne(any());
    }
}
