package com.cognora.platform.inventory.service.replenishment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 再订货点计算（规则版，纯函数）。
 *
 * <p>公式（设计稿 §5，MVP 近似）：
 * <pre>
 * SS         = μ × safetyDays
 * ROP        = μ × L + SS
 * targetStock = μ × (L + reviewPeriodDays) + SS
 * Q_raw      = targetStock − availableStock − inTransitQty
 * 触发        = (availableStock + inTransitQty) ≤ ROP 且 Q_raw &gt; 0
 * </pre>
 *
 * <p><b>为什么是纯函数：</b>补货算量是这门生意里最需要被质疑的一段——它会直接变成
 * 采购建议。把公式和数据访问分开之后，回测、单测和「这个数是怎么来的」的解释都可以
 * 只针对这一段做，不需要起 Spring 上下文或连数据库。
 *
 * <p><b>在途量的作用：</b>触发判定用的是 {@code available + inTransit}，缺口用的是
 * {@code target − available − inTransit}。少了这一项，同一批货在到货前每个复核周期都
 * 会被重复建议一次。
 *
 * <p><b>精度：</b>内部一律 {@link BigDecimal}，最后向下取整为整数件（不足一件不补），
 * 避免浮点误差把 0.9999 提成 1 件。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public final class ReorderPointCalculator {

  /** 计算结果：中间量与最终结论一起返回，供人审解释与快照落库。 */
  public record Result(
      BigDecimal dailyDemand,
      BigDecimal safetyStock,
      BigDecimal reorderPoint,
      BigDecimal targetStock,
      BigDecimal rawQuantity,
      int suggestedQuantity,
      boolean triggered,
      String reason) {
  }

  private ReorderPointCalculator() {
  }

  /**
   * 计算再订货点与目标水位补货量。
   *
   * @param dailyDemand     加权移动平均日需求 μ（&ge;0）
   * @param leadTimeDays    供应交期 L（天，&ge;0）
   * @param reviewPeriodDays 复核周期（天，&ge;0）
   * @param safetyDays      安全库存天数（&ge;0）
   * @param availableStock  可用库存
   * @param inTransitQty    在途量（采购单未收货 + 已采纳未收货）
   * @return 计算结果；{@code triggered=false} 时 {@code suggestedQuantity=0}
   * @throws IllegalArgumentException 入参为负或为空
   */
  public static Result calculate(BigDecimal dailyDemand,
                                 int leadTimeDays,
                                 int reviewPeriodDays,
                                 BigDecimal safetyDays,
                                 int availableStock,
                                 int inTransitQty) {
    BigDecimal demand = nonNegative(dailyDemand, "日需求 μ");
    BigDecimal ssDays = nonNegative(safetyDays, "安全库存天数");
    if (leadTimeDays < 0) {
      throw new IllegalArgumentException("交期不能为负数: " + leadTimeDays);
    }
    if (reviewPeriodDays < 0) {
      throw new IllegalArgumentException("复核周期不能为负数: " + reviewPeriodDays);
    }
    if (availableStock < 0) {
      throw new IllegalArgumentException("可用库存不能为负数: " + availableStock);
    }
    if (inTransitQty < 0) {
      throw new IllegalArgumentException("在途量不能为负数: " + inTransitQty);
    }

    BigDecimal safetyStock = demand.multiply(ssDays).setScale(4, RoundingMode.HALF_UP);
    BigDecimal leadTimeDemand = demand.multiply(BigDecimal.valueOf(leadTimeDays));
    BigDecimal reorderPoint = leadTimeDemand.add(safetyStock).setScale(4, RoundingMode.HALF_UP);

    BigDecimal coverageDays = BigDecimal.valueOf((long) leadTimeDays + reviewPeriodDays);
    BigDecimal targetStock = demand.multiply(coverageDays).add(safetyStock)
        .setScale(4, RoundingMode.HALF_UP);

    BigDecimal stockOnHand = BigDecimal.valueOf((long) availableStock + inTransitQty);
    BigDecimal rawQuantity = targetStock.subtract(stockOnHand).setScale(4, RoundingMode.HALF_UP);

    boolean triggered = stockOnHand.compareTo(reorderPoint) <= 0
        && rawQuantity.compareTo(BigDecimal.ZERO) > 0;

    if (!triggered) {
      String reason;
      if (stockOnHand.compareTo(reorderPoint) > 0) {
        reason = "库存充足：可用+在途 " + stockOnHand.stripTrailingZeros().toPlainString()
            + " > ROP " + reorderPoint.stripTrailingZeros().toPlainString();
      } else {
        reason = "缺口已由在途覆盖：Q_raw=" + rawQuantity.stripTrailingZeros().toPlainString()
            + " ≤ 0";
      }
      return new Result(demand, safetyStock, reorderPoint, targetStock, rawQuantity, 0,
          false, reason);
    }

    int suggested = rawQuantity.setScale(0, RoundingMode.FLOOR).intValueExact();
    String reason = "触发补货：可用+在途 " + stockOnHand.stripTrailingZeros().toPlainString()
        + " ≤ ROP " + reorderPoint.stripTrailingZeros().toPlainString()
        + "，目标水位 " + targetStock.stripTrailingZeros().toPlainString()
        + "，建议量 " + suggested;
    return new Result(demand, safetyStock, reorderPoint, targetStock, rawQuantity, suggested,
        true, reason);
  }

  private static BigDecimal nonNegative(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException(label + " 不能为空");
    }
    if (value.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException(label + " 不能为负数: " + value.toPlainString());
    }
    return value;
  }
}
