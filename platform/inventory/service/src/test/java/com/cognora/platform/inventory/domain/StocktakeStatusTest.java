package com.cognora.platform.inventory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘点状态机用例：合法流转放行、终态不可再转、跳步被拒。
 */
class StocktakeStatusTest {

  @Test
  @DisplayName("DRAFT 只能提交或取消")
  void draftTransitions() {
    assertTrue(StocktakeStatus.DRAFT.canTransitionTo(StocktakeStatus.SUBMITTED));
    assertTrue(StocktakeStatus.DRAFT.canTransitionTo(StocktakeStatus.CANCELLED));
    assertFalse(StocktakeStatus.DRAFT.canTransitionTo(StocktakeStatus.APPROVED));
    assertFalse(StocktakeStatus.DRAFT.canTransitionTo(StocktakeStatus.POSTED));
  }

  @Test
  @DisplayName("SUBMITTED 可审批/驳回/取消")
  void submittedTransitions() {
    assertTrue(StocktakeStatus.SUBMITTED.canTransitionTo(StocktakeStatus.APPROVED));
    assertTrue(StocktakeStatus.SUBMITTED.canTransitionTo(StocktakeStatus.REJECTED));
    assertTrue(StocktakeStatus.SUBMITTED.canTransitionTo(StocktakeStatus.CANCELLED));
    assertFalse(StocktakeStatus.SUBMITTED.canTransitionTo(StocktakeStatus.POSTED));
  }

  @Test
  @DisplayName("APPROVED 才能落账")
  void approvedCanPost() {
    assertTrue(StocktakeStatus.APPROVED.canTransitionTo(StocktakeStatus.POSTED));
    assertFalse(StocktakeStatus.APPROVED.canTransitionTo(StocktakeStatus.DRAFT));
  }

  @Test
  @DisplayName("终态不可再流转，且拒绝 null")
  void terminalStatesAreFinal() {
    for (StocktakeStatus terminal : new StocktakeStatus[]{
        StocktakeStatus.POSTED, StocktakeStatus.REJECTED, StocktakeStatus.CANCELLED}) {
      assertTrue(terminal.isTerminal(), terminal + " 应为终态");
      for (StocktakeStatus target : StocktakeStatus.values()) {
        assertFalse(terminal.canTransitionTo(target),
            terminal + " 不应能转到 " + target);
      }
      assertFalse(terminal.canTransitionTo(null));
    }
  }
}
