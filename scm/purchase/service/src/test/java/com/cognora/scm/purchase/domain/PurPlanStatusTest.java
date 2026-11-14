package com.cognora.scm.purchase.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采购计划状态机用例：钉死 0-4 的编码含义、合法流转与终态。
 *
 * <p>这些断言直接对应修复前 {@code PurPlanCommandService} 的两个 bug：
 * {@code complete()} 用 {@code APPROVED→REJECTED} 校验却把 status 写成 3，
 * {@code startExecution()} 做了一次 {@code APPROVED→APPROVED} 自转。
 */
class PurPlanStatusTest {

  @Test
  @DisplayName("编码与 purchase_001 迁移一致：0编制中 1待审批 2已审批 3已完成 4执行中")
  void codesMatchMigration() {
    assertThat(PurPlanStatus.DRAFT.getCode()).isZero();
    assertThat(PurPlanStatus.PENDING_APPROVAL.getCode()).isEqualTo(1);
    assertThat(PurPlanStatus.APPROVED.getCode()).isEqualTo(2);
    assertThat(PurPlanStatus.COMPLETED.getCode()).isEqualTo(3);
    assertThat(PurPlanStatus.EXECUTING.getCode()).isEqualTo(4);

    assertThat(PurPlanStatus.fromCode(4)).isEqualTo(PurPlanStatus.EXECUTING);
    assertThat(PurPlanStatus.fromCode(3)).isEqualTo(PurPlanStatus.COMPLETED);
    assertThat(PurPlanStatus.fromCode(null)).isNull();
    assertThat(PurPlanStatus.fromCode(99)).isNull();
  }

  @Test
  @DisplayName("DRAFT 只能提交")
  void draftTransitions() {
    assertTrue(PurPlanStatus.DRAFT.canTransitionTo(PurPlanStatus.PENDING_APPROVAL));
    assertFalse(PurPlanStatus.DRAFT.canTransitionTo(PurPlanStatus.APPROVED));
    assertFalse(PurPlanStatus.DRAFT.canTransitionTo(PurPlanStatus.EXECUTING));
  }

  @Test
  @DisplayName("PENDING_APPROVAL 只能审批通过")
  void pendingTransitions() {
    assertTrue(PurPlanStatus.PENDING_APPROVAL.canTransitionTo(PurPlanStatus.APPROVED));
    assertFalse(PurPlanStatus.PENDING_APPROVAL.canTransitionTo(PurPlanStatus.EXECUTING));
    assertFalse(PurPlanStatus.PENDING_APPROVAL.canTransitionTo(PurPlanStatus.COMPLETED));
  }

  @Test
  @DisplayName("APPROVED 只能开始执行，不能直接完成，也不能自转空操作")
  void approvedTransitions() {
    assertTrue(PurPlanStatus.APPROVED.canTransitionTo(PurPlanStatus.EXECUTING));
    // 修复点1：旧的 startExecution 用 APPROVED→APPROVED 表示「开始执行」，必须被拒。
    assertFalse(PurPlanStatus.APPROVED.canTransitionTo(PurPlanStatus.APPROVED));
    // 修复点2：完成必须先执行，不能从 APPROVED 一步跳到 COMPLETED。
    assertFalse(PurPlanStatus.APPROVED.canTransitionTo(PurPlanStatus.COMPLETED));
  }

  @Test
  @DisplayName("EXECUTING 才能完成")
  void executingTransitions() {
    assertTrue(PurPlanStatus.EXECUTING.canTransitionTo(PurPlanStatus.COMPLETED));
    assertFalse(PurPlanStatus.EXECUTING.canTransitionTo(PurPlanStatus.APPROVED));
    assertFalse(PurPlanStatus.EXECUTING.canTransitionTo(PurPlanStatus.EXECUTING));
  }

  @Test
  @DisplayName("COMPLETED 是唯一终态，不可再流转且拒绝 null")
  void terminalStatesAreFinal() {
    assertTrue(PurPlanStatus.COMPLETED.isTerminal());
    for (PurPlanStatus target : PurPlanStatus.values()) {
      assertFalse(PurPlanStatus.COMPLETED.canTransitionTo(target),
          "COMPLETED 不应能转到 " + target);
    }
    assertFalse(PurPlanStatus.COMPLETED.canTransitionTo(null));
  }

  @Test
  @DisplayName("非终态 isTerminal 为 false")
  void nonTerminalStates() {
    assertFalse(PurPlanStatus.DRAFT.isTerminal());
    assertFalse(PurPlanStatus.PENDING_APPROVAL.isTerminal());
    assertFalse(PurPlanStatus.APPROVED.isTerminal());
    assertFalse(PurPlanStatus.EXECUTING.isTerminal());
  }
}
