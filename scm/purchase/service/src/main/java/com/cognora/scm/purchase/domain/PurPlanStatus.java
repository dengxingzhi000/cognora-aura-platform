package com.cognora.scm.purchase.domain;

import java.util.Set;

/**
 * 采购计划状态机（{@code pur_plan.status} 的强类型镜像）。
 *
 * <p>编码与含义（由 {@code purchase_001_plan_executing_status.sql} 定义）：
 * <pre>
 * 0 = 编制中     DRAFT
 * 1 = 待审批     PENDING_APPROVAL
 * 2 = 已审批可执行 APPROVED
 * 3 = 已完成     COMPLETED（终态）
 * 4 = 执行中     EXECUTING
 * </pre>
 *
 * <p>合法流转：
 * <pre>
 * DRAFT            → PENDING_APPROVAL    (submit)
 * PENDING_APPROVAL → APPROVED           (approve)
 * APPROVED         → EXECUTING          (startExecution)
 * EXECUTING        → COMPLETED          (complete)
 * </pre>
 *
 * <p><b>修复的两个 bug：</b>
 * <ul>
 *   <li>{@code complete()} 此前校验 {@code APPROVED→REJECTED} 却把 status 写成 3。
 *       3 在建表语义里是「已完成」，所以那次落库「碰巧」写对了值、却走了驳回校验：
 *       在配了状态字典的环境里直接抛异常，在没配的环境里把计划标成完成而不经过执行。
 *       现在完成是 {@code EXECUTING(4) → COMPLETED(3)}。</li>
 *   <li>{@code startExecution()} 此前是 {@code APPROVED→APPROVED} 的空转（同一状态
 *       自转被状态机判非法），因为库里没有「执行中」编码。新增 4 之后，
 *       开始执行是一条真实的两步流转，而不是空操作。</li>
 * </ul>
 *
 * <p>为什么「执行中」用 4 而不是复用 2：2 在历史数据里已经表示「已审批」，
 * 复用会让「审批完还没开始执行」与「正在执行」不可区分。追加编码只放宽约束、
 * 不迁移数据，历史行语义不变。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public enum PurPlanStatus {

  DRAFT(0),
  PENDING_APPROVAL(1),
  APPROVED(2),
  COMPLETED(3),
  EXECUTING(4);

  private static final Set<PurPlanStatus> TERMINAL = Set.of(COMPLETED);

  private final int code;

  PurPlanStatus(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }

  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }

  /**
   * 按库内编码解析；未知编码返回 {@code null}，由调用方报错而不是猜测。
   */
  public static PurPlanStatus fromCode(Integer code) {
    if (code == null) {
      return null;
    }
    for (PurPlanStatus status : values()) {
      if (status.code == code) {
        return status;
      }
    }
    return null;
  }

  public boolean canTransitionTo(PurPlanStatus target) {
    if (target == null || this.isTerminal()) {
      return false;
    }
    return switch (this) {
      case DRAFT -> target == PENDING_APPROVAL;
      case PENDING_APPROVAL -> target == APPROVED;
      case APPROVED -> target == EXECUTING;
      case EXECUTING -> target == COMPLETED;
      default -> false;
    };
  }
}
