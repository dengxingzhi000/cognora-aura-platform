package com.cognora.platform.inventory.service.replenishment;

import com.cognora.platform.inventory.domain.ReplenishmentFlags;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 补货量约束裁剪（规则版，纯函数）。
 *
 * <p>把「模型算出来的缺口」裁剪成「真的能下的量」，并如实报告裁剪原因：
 * <ol>
 *   <li>仓容上限：{@code available + onOrder + Q ≤ maxStock}</li>
 *   <li>预算上限：{@code Q × unitPrice ≤ remainingBudget}</li>
 *   <li>MOQ：低于最小起订量时抬到 MOQ（但不能再突破仓容/预算）</li>
 *   <li>包装倍数：向上取整到 {@code packMultiple} 的整数倍（同样受上面两个上限约束）</li>
 * </ol>
 *
 * <p><b>设计取舍——为什么不静默丢弃：</b>冲突时返回「可行量 + 原因」。裁剪到 0 也返回 0
 * 并带上命中原因，由调用方决定是标记人审还是跳过该行；把行悄悄删掉会让「为什么这个
 * SKU 没被补货」无从回答。
 *
 * <p><b>顺序为什么重要：</b>先按仓容/预算求出上限，再向上取整到 MOQ/包装倍数，并且在
 * 取整后<b>重新校验</b>上限。反过来（先取整再裁剪）会把量裁成一个不满足包装倍数、
 * 供应商不会接的数字。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public final class ReplenishmentConstraintChecker {

  /**
   * 约束输入。
   *
   * @param rawQuantity    模型算出的原始缺口（&gt;0 才会被调用）
   * @param minOrderQty    供应商最小起订量（&gt;=1）
   * @param packMultiple   包装倍数（&gt;=1）
   * @param maxStock       台账最大库存；null 表示不限
   * @param currentStock   当前可用量，用于算仓容剩余
   * @param onOrderQty     在途量，计入仓容占用
   * @param unitPrice      采购单价；null 或 0 表示无预算约束
   * @param budgetRemaining 本周期剩余预算；null 表示不限
   */
  public record Input(
      int rawQuantity,
      int minOrderQty,
      int packMultiple,
      Integer maxStock,
      int currentStock,
      int onOrderQty,
      BigDecimal unitPrice,
      BigDecimal budgetRemaining) {
  }

  /**
   * 约束输出。
   *
   * @param feasibleQuantity 裁剪后的可行量；0 表示本轮不可补
   * @param flags            命中的约束提示位（取整/仓容/预算）
   * @param reason           人审可读的裁剪说明
   */
  public record Result(int feasibleQuantity, Set<ReplenishmentFlags> flags, String reason) {
  }

  private ReplenishmentConstraintChecker() {
  }

  public static Result apply(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("约束输入不能为空");
    }
    int raw = input.rawQuantity();
    if (raw <= 0) {
      return new Result(0, Set.of(), "缺口为 0，无需补货");
    }
    int moq = Math.max(1, input.minOrderQty());
    int pack = Math.max(1, input.packMultiple());

    Set<ReplenishmentFlags> flags = new LinkedHashSet<>();
    StringBuilder reason = new StringBuilder();

    // 1) 仓容上限：可用 + 在途 + 补货 ≤ maxStock
    int capacityLimit = Integer.MAX_VALUE;
    if (input.maxStock() != null) {
      capacityLimit = input.maxStock() - input.currentStock() - input.onOrderQty();
      if (capacityLimit < 0) {
        capacityLimit = 0;
      }
    }

    // 2) 预算上限：按单价换算成能买多少件
    int budgetLimit = Integer.MAX_VALUE;
    BigDecimal unitPrice = input.unitPrice();
    BigDecimal budget = input.budgetRemaining();
    if (unitPrice != null && unitPrice.compareTo(BigDecimal.ZERO) > 0 && budget != null) {
      if (budget.compareTo(BigDecimal.ZERO) <= 0) {
        budgetLimit = 0;
      } else {
        budgetLimit = budget.divide(unitPrice, 0, RoundingMode.FLOOR).intValueExact();
      }
    }

    int capped = Math.min(raw, Math.min(capacityLimit, budgetLimit));
    if (capped < raw) {
      if (capacityLimit <= budgetLimit && capacityLimit < raw) {
        flags.add(ReplenishmentFlags.CAPPED_BY_CAPACITY);
        reason.append("仓容上限裁剪到 ").append(capacityLimit)
            .append("（maxStock=").append(input.maxStock())
            .append("，现有=").append(input.currentStock())
            .append("，在途=").append(input.onOrderQty()).append("）；");
      }
      if (budgetLimit < raw && budgetLimit < capacityLimit) {
        flags.add(ReplenishmentFlags.CAPPED_BY_BUDGET);
        reason.append("预算上限裁剪到 ").append(budgetLimit).append("；");
      } else if (budgetLimit == capacityLimit && budgetLimit < raw) {
        flags.add(ReplenishmentFlags.CAPPED_BY_BUDGET);
        reason.append("预算上限同时裁剪到 ").append(budgetLimit).append("；");
      }
    }

    if (capped <= 0) {
      reason.append("可行量为 0，本轮不补");
      return new Result(0, flags, reason.toString());
    }

    // 3) MOQ 与包装倍数：向上取整，但不能突破仓容/预算上限
    int rounded = roundUp(capped, moq, pack);
    if (rounded > capped) {
      int ceiling = Math.min(capacityLimit, budgetLimit);
      if (rounded > ceiling) {
        // 取整会突破上限：退回「不超过上限的最大合法倍数」，仍然合规。
        rounded = roundDown(ceiling, moq, pack);
        if (rounded <= 0) {
          reason.append("取整到 MOQ/包装倍数后超出仓容或预算，本轮不补；");
          return new Result(0, flags, reason.toString());
        }
        reason.append("取整受上限约束回退到 ").append(rounded).append("；");
      }
      if (rounded > capped) {
        flags.add(ReplenishmentFlags.ROUNDED_TO_MOQ);
        reason.append("按 MOQ=").append(moq).append("/包装倍数=").append(pack)
            .append(" 向上取整为 ").append(rounded).append("；");
      }
    }

    if (reason.length() == 0) {
      reason.append("未命中约束，按模型建议量 ").append(rounded);
    }
    return new Result(rounded, flags, reason.toString());
  }

  /**
   * 向上取整到同时满足 MOQ 与包装倍数的最小值。
   *
   * <p>取 {@code moq} 与 {@code pack} 的最小公倍数作为步长：两者都满足才是一个
   * 供应商真正会接受的量。
   */
  static int roundUp(int quantity, int moq, int pack) {
    if (quantity <= 0) {
      return 0;
    }
    int step = lcm(moq, pack);
    int units = (quantity + step - 1) / step;
    int rounded = Math.multiplyExact(units, step);
    // MOQ 是「至少」语义：即使 quantity 已整除 step，也不得低于 MOQ。
    return Math.max(rounded, moq);
  }

  /** 不超过 {@code ceiling} 的最大合法倍数；无合法值时返回 0。 */
  static int roundDown(int ceiling, int moq, int pack) {
    if (ceiling <= 0) {
      return 0;
    }
    int step = lcm(moq, pack);
    int rounded = (ceiling / step) * step;
    if (rounded < moq) {
      return 0;
    }
    return rounded;
  }

  private static int lcm(int a, int b) {
    return a / gcd(a, b) * b;
  }

  private static int gcd(int a, int b) {
    int x = Math.abs(a);
    int y = Math.abs(b);
    while (y != 0) {
      int t = x % y;
      x = y;
      y = t;
    }
    return x == 0 ? 1 : x;
  }
}
