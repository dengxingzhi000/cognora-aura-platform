package com.cognora.platform.inventory.domain;

import java.util.Set;

/**
 * 补货建议单状态机。
 *
 * <pre>
 * DRAFT     → ADOPTED / REJECTED / CANCELLED
 * ADOPTED   → CONVERTED / CANCELLED
 * CONVERTED / REJECTED / CANCELLED 均为终态
 * </pre>
 *
 * <p>与 {@link StocktakeStatus} 同款写法：只有 ADOPTED 可以转采购计划，
 * 因此「人审通过」与「已经生成采购计划」是两件事，前者不代表下游一定成功。
 *
 * <p>不变量：
 * <ul>
 *   <li>只有 DRAFT 可以改量/换供应商（{@code ReplenishmentSuggestionCommandService}
 *       在 ADOPTED 之后拒绝修改，避免采纳的量与转出的量不一致）；</li>
 *   <li>CONVERTED 之后建议单不可再动，重复转换由业务键与状态机双重挡下。</li>
 * </ul>
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public enum ReplenishmentStatus {

  DRAFT,
  ADOPTED,
  CONVERTED,
  REJECTED,
  CANCELLED;

  private static final Set<ReplenishmentStatus> TERMINAL =
      Set.of(CONVERTED, REJECTED, CANCELLED);

  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }

  public boolean canTransitionTo(ReplenishmentStatus target) {
    if (target == null || this.isTerminal()) {
      return false;
    }
    return switch (this) {
      case DRAFT -> target == ADOPTED || target == REJECTED || target == CANCELLED;
      case ADOPTED -> target == CONVERTED || target == CANCELLED;
      default -> false;
    };
  }

  /** 只有草稿状态的建议单允许人审改量/换供应商。 */
  public boolean isEditable() {
    return this == DRAFT;
  }

  /**
   * 从持久化的字符串还原状态；未知值直接失败，避免把脏数据当成 DRAFT 放行。
   */
  public static ReplenishmentStatus from(String value) {
    if (value == null) {
      throw new IllegalArgumentException("补货建议单状态为空");
    }
    for (ReplenishmentStatus status : values()) {
      if (status.name().equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("未知的补货建议单状态: " + value);
  }
}
