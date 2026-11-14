package com.cognora.platform.inventory.domain;

import java.util.Set;

/**
 * 盘点单据状态机。
 *
 * <pre>
 * DRAFT     → SUBMITTED / CANCELLED
 * SUBMITTED → APPROVED  / REJECTED / CANCELLED
 * APPROVED  → POSTED    / CANCELLED
 * POSTED / REJECTED / CANCELLED 均为终态
 * </pre>
 *
 * <p>只有 APPROVED → POSTED 会真正写库存差异；其余流转只改单据状态，
 * 因此审批与落账可以分权，避免录盘的人同时决定账本变化。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
public enum StocktakeStatus {

  DRAFT,
  SUBMITTED,
  APPROVED,
  POSTED,
  REJECTED,
  CANCELLED;

  private static final Set<StocktakeStatus> TERMINAL =
      Set.of(POSTED, REJECTED, CANCELLED);

  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }

  public boolean canTransitionTo(StocktakeStatus target) {
    if (target == null || this.isTerminal()) {
      return false;
    }
    return switch (this) {
      case DRAFT -> target == SUBMITTED || target == CANCELLED;
      case SUBMITTED -> target == APPROVED || target == REJECTED || target == CANCELLED;
      case APPROVED -> target == POSTED || target == CANCELLED;
      default -> false;
    };
  }
}
