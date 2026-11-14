package com.cognora.platform.inventory.service.replenishment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 加权移动平均需求（纯函数）。
 *
 * <p>近期需求比远期更能代表「下一期要补多少」，因此按天赋线性递增权重：
 * 最老的一天权重 1、最新的一天权重 N，{@code μ = Σ(qᵢ×wᵢ) / Σwᵢ}。
 *
 * <p><b>为什么按自然日补齐缺失天：</b>直接对「有出库的那几天」求平均，会在
 * 「一周只卖两次」的商品上把 μ 抬高近一倍——没出库的日子也是需求为 0 的日子。
 * 因此先把区间内每一天补成 0 再算加权，缺的那几天按权重 0 计入分母。
 *
 * <p><b>冷启动：</b>有出库记录的天数少于 {@code minSampleDays} 时返回
 * {@code confidence=LOW_CONFIDENCE}，由调用方在建议单上标 {@code NEEDS_MANUAL}。
 * 规则版不编造同类目均值——没有同类目数据支撑时，「用相邻均价猜一个新品的需求」
 * 只会给出一个看起来有依据的数字。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public final class WeightedMovingAverage {

  /** 默认回看天数。 */
  public static final int DEFAULT_LOOKBACK_DAYS = 28;

  /** 判定「样本足够」所需的最少有出库天数。 */
  public static final int DEFAULT_MIN_SAMPLE_DAYS = 7;

  /**
   * 预测结果。
   *
   * @param dailyDemand      加权移动平均日需求 μ
   * @param sampleDays       区间内<b>有出库</b>的天数
   * @param lookbackDays     区间总天数
   * @param lowConfidence    样本不足，需人工确认
   */
  public record Result(BigDecimal dailyDemand, int sampleDays, int lookbackDays,
                       boolean lowConfidence) {
  }

  private WeightedMovingAverage() {
  }

  /**
   * 按日序列计算加权移动平均。
   *
   * @param dailyOutbound  完整区间内每天的出库量（缺失天用 0 或 null 表示）
   * @param minSampleDays  判定样本足够的最少出库天数
   */
  public static Result calculate(List<BigDecimal> dailyOutbound, int minSampleDays) {
    if (dailyOutbound == null || dailyOutbound.isEmpty()) {
      return new Result(BigDecimal.ZERO, 0, 0, true);
    }
    int n = dailyOutbound.size();
    BigDecimal weightedSum = BigDecimal.ZERO;
    long weightSum = 0L;
    int sampleDays = 0;

    for (int i = 0; i < n; i++) {
      BigDecimal qty = dailyOutbound.get(i);
      BigDecimal safeQty = qty == null ? BigDecimal.ZERO : qty;
      if (safeQty.compareTo(BigDecimal.ZERO) < 0) {
        throw new IllegalArgumentException("日出库量不能为负数: " + safeQty.toPlainString());
      }
      if (safeQty.compareTo(BigDecimal.ZERO) > 0) {
        sampleDays++;
      }
      // 越新的天权重越大：第 i 天（0 基）权重 i+1
      long weight = i + 1L;
      weightedSum = weightedSum.add(safeQty.multiply(BigDecimal.valueOf(weight)));
      weightSum += weight;
    }

    BigDecimal demand = weightSum == 0
        ? BigDecimal.ZERO
        : weightedSum.divide(BigDecimal.valueOf(weightSum), MathContext.DECIMAL64)
            .setScale(4, RoundingMode.HALF_UP);

    boolean lowConfidence = sampleDays < Math.max(1, minSampleDays);
    return new Result(demand, sampleDays, n, lowConfidence);
  }

  /**
   * 把「稀疏的 日期→数量」映射补齐成从 {@code start} 到 {@code end}（含）的连续日序列。
   *
   * <p>这是本类与 SQL 聚合结果之间的唯一转换点：SQL 只返回有出库的天，缺的天在这里补 0。
   *
   * @param series 稀疏出库量（key=日期）
   * @param start  区间起（含）
   * @param end    区间止（含）
   */
  public static List<BigDecimal> densify(Map<LocalDate, BigDecimal> series,
                                         LocalDate start, LocalDate end) {
    if (start == null || end == null || end.isBefore(start)) {
      return List.of();
    }
    Map<LocalDate, BigDecimal> safe = series == null ? Map.of() : new HashMap<>(series);
    List<BigDecimal> result = new java.util.ArrayList<>();
    for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
      BigDecimal qty = safe.get(day);
      result.add(qty == null ? BigDecimal.ZERO : qty);
    }
    return result;
  }
}
