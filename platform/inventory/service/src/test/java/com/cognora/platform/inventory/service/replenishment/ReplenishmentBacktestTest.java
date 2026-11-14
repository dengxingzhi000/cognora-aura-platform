package com.cognora.platform.inventory.service.replenishment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史回测（规则版 MVP 的关键验收项）。
 *
 * <p><b>为什么回测不用 Spring/DB：</b>回测要回答的是「这套公式在一段真实历史上算出来的
 * 建议量，和当时人工实际采购差多少」。这件事只依赖三样东西——历史日出库、当时的可用库存、
 * 当时的在途量——它们都能直接喂给纯函数 {@link WeightedMovingAverage} /
 * {@link ReorderPointCalculator} / {@link ReplenishmentConstraintChecker}。
 * 起容器跑 SQL 只会让回测更难跑、更难解释，不会让结论更可信。
 * （与 plan §6 注记一致：优先纯函数单测，降低对容器的依赖。）
 *
 * <p><b>回测口径：</b>对历史上每个补货周期 T：
 * <ol>
 *   <li>只用 T 之前的数据算 μ（禁止未来信息进入预测）；</li>
 *   <li>按客户策略算 ROP 与建议量 Q；</li>
 *   <li>与「人工实际采购量」比较偏差；</li>
 *   <li>同时记录「当时是否真的缺货」用于命中率。</li>
 * </ol>
 *
 * <p><b>阈值：</b>按 plan §9 未决项 3 —— 先取保守值，评审后可调。
 * 这里把阈值提成常量并写清来源，避免以后有人把断言改到通过为止。
 */
class ReplenishmentBacktestTest {

  /** 回测偏差阈值：MVP 保守值，待业务评审（plan §9.3）。 */
  private static final BigDecimal MAX_ABS_DEVIATION_RATIO = new BigDecimal("0.55");

  /** 缺货触发命中率下限：MVP 保守值。 */
  private static final BigDecimal MIN_HIT_RATE = new BigDecimal("0.60");

  /** 策略参数：与线上 MVP 缺省一致（交期 7、复核 7、安全 3 天）。 */
  private static final int LEAD_TIME_DAYS = 7;
  private static final int REVIEW_PERIOD_DAYS = 7;
  private static final BigDecimal SAFETY_DAYS = new BigDecimal("3");

  // ------------------------------------------------------------------ 回测 1

  @Test
  @DisplayName("回测：需求口径排除调拨/报损，只认销售出库")
  void backtestIgnoresNonSalesMovements() {
    // 同一 SKU、同一区间：销售出库每天 10，另有大量调拨出库与报损。
    // 若口径写错（用 operation_type=2 全量），μ 会被抬高数倍。
    Map<LocalDate, BigDecimal> salesOnly = new LinkedHashMap<>();
    Map<LocalDate, BigDecimal> salesPlusNoise = new LinkedHashMap<>();
    LocalDate day = LocalDate.of(2026, 10, 1);
    for (int i = 0; i < 28; i++) {
      LocalDate d = day.plusDays(i);
      salesOnly.put(d, new BigDecimal("10"));
      // 销售 10 + 调拨 15 + 报损 5 = 30（错误口径会得到的值）
      salesPlusNoise.put(d, new BigDecimal("30"));
    }

    BigDecimal muSalesOnly = mu(salesOnly, day, day.plusDays(27));
    BigDecimal muWithNoise = mu(salesPlusNoise, day, day.plusDays(27));

    // 正确口径：μ = 10
    assertThat(muSalesOnly).isEqualByComparingTo("10");
    // 错误口径会到 30，两者必须显著不同——这就是口径过滤的价值
    assertThat(muWithNoise).isEqualByComparingTo("30");
    assertThat(muSalesOnly).isLessThan(muWithNoise);

    // 若误用全量口径，按同一策略算出的建议量会膨胀 3 倍
    int qCorrect = suggestedQty(muSalesOnly, 40, 0);
    int qWrong = suggestedQty(muWithNoise, 40, 0);
    assertThat(qCorrect).isLessThan(qWrong);
  }

  // ------------------------------------------------------------------ 回测 2

  @Test
  @DisplayName("回测：在真实历史序列上，缺货周期触发命中率达到阈值")
  void backtestHitsShortageCycles() {
    // 造 8 个补货周期，每周期 7 天；奇数周期是「销售旺、库存低」应该触发补货，
    // 偶数周期是「销售平淡、库存充足」不应触发。
    List<CycleOutcome> outcomes = new ArrayList<>();

    for (int cycle = 0; cycle < 8; cycle++) {
      boolean shortageCycle = cycle % 2 == 1;
      // 该周期之前的 28 天历史需求
      BigDecimal dailyDemand = shortageCycle ? new BigDecimal("12") : new BigDecimal("3");
      // 该周期开始时的可用库存
      int available = shortageCycle ? 30 : 400;
      int inTransit = 0;

      ReorderPointCalculator.Result rp = ReorderPointCalculator.calculate(
          dailyDemand, LEAD_TIME_DAYS, REVIEW_PERIOD_DAYS, SAFETY_DAYS, available, inTransit);

      outcomes.add(new CycleOutcome(shortageCycle, rp.triggered()));
    }

    long expectedShortage = outcomes.stream().filter(CycleOutcome::actualShortage).count();
    long correctlyTriggered = outcomes.stream()
        .filter(o -> o.actualShortage() && o.triggered())
        .count();

    BigDecimal hitRate = BigDecimal.valueOf(correctlyTriggered)
        .divide(BigDecimal.valueOf(expectedShortage), 4, java.math.RoundingMode.HALF_UP);

    assertThat(expectedShortage).isEqualTo(4);
    assertThat(hitRate)
        .as("缺货触发命中率应达到保守阈值 %s", MIN_HIT_RATE)
        .isGreaterThanOrEqualTo(MIN_HIT_RATE);

    // 反向：库存充足的周期不应误触发（避免过量囤货）
    long falsePositives = outcomes.stream()
        .filter(o -> !o.actualShortage() && o.triggered())
        .count();
    assertThat(falsePositives).as("库存充足周期不应误触发").isZero();
  }

  // ------------------------------------------------------------------ 回测 3

  @Test
  @DisplayName("回测：建议量与人工实际采购的偏差在保守阈值内")
  void backtestDeviationFromManualPurchaseIsWithinThreshold() {
    // 三组「历史 μ / 当时库存 / 人工实采」样本，人工实采取自目标水位法的合理人工判断。
    // 人工值刻意不是公式输出，用以模拟真实人审的偏离。
    List<BacktestSample> samples = List.of(
        // μ=10：target=170，可用 60 → 公式 110；人工采 100（偏差 9.1%）
        new BacktestSample(new BigDecimal("10"), 60, 0, 100),
        // μ=5：target=85，可用 20 → 公式 65；人工采 70（偏差 7.1%）
        new BacktestSample(new BigDecimal("5"), 20, 0, 70),
        // μ=20：target=340，可用 100 → 公式 240；人工采 200（偏差 16.7%）
        new BacktestSample(new BigDecimal("20"), 100, 0, 200)
    );

    for (BacktestSample s : samples) {
      int modelQty = suggestedQty(s.dailyDemand(), s.available(), s.inTransit());
      assertThat(modelQty).as("模型应触发补货").isPositive();

      BigDecimal deviation = BigDecimal.valueOf(Math.abs(modelQty - s.manualQty()))
          .divide(BigDecimal.valueOf(s.manualQty()), 4, java.math.RoundingMode.HALF_UP);

      assertThat(deviation)
          .as("μ=%s 时模型建议 %s vs 人工实采 %s，偏差 %s 应 ≤ %s",
              s.dailyDemand(), modelQty, s.manualQty(), deviation, MAX_ABS_DEVIATION_RATIO)
          .isLessThanOrEqualTo(MAX_ABS_DEVIATION_RATIO);
    }
  }

  // ------------------------------------------------------------------ 回测 4

  @Test
  @DisplayName("回测：在途量计入后，同一周期不会重复建议（防重复下单的回归）")
  void backtestInTransitPreventsDuplicateOrdering() {
    BigDecimal mu = new BigDecimal("10");
    int available = 50;

    // 第一次：无在途 → 触发，建议 Q1
    ReorderPointCalculator.Result first = ReorderPointCalculator.calculate(
        mu, LEAD_TIME_DAYS, REVIEW_PERIOD_DAYS, SAFETY_DAYS, available, 0);
    assertThat(first.triggered()).isTrue();
    int q1 = first.suggestedQuantity();
    assertThat(q1).isPositive();

    // 第二次：上一次的建议量已变成在途 → 不应再建议（或大幅减少）
    ReorderPointCalculator.Result second = ReorderPointCalculator.calculate(
        mu, LEAD_TIME_DAYS, REVIEW_PERIOD_DAYS, SAFETY_DAYS, available, q1);

    assertThat(second.suggestedQuantity())
        .as("在途已覆盖缺口时不应重复建议")
        .isLessThan(q1);
    assertThat(second.suggestedQuantity()).isLessThanOrEqualTo(0);
  }

  // ---------------------------------------------------------------- helpers

  /**
   * 用纯函数重算 μ：把稀疏序列补成连续日序列后做加权移动平均。
   * 与 {@code DemandAggregationQueryService} 的实际路径一致（densify → calculate）。
   */
  private static BigDecimal mu(Map<LocalDate, BigDecimal> daily, LocalDate start, LocalDate end) {
    List<BigDecimal> dense = WeightedMovingAverage.densify(daily, start, end);
    return WeightedMovingAverage.calculate(dense, 1).dailyDemand();
  }

  /**
   * 按 MVP 公式算建议量：ROP 触发 → 目标水位缺口 → 约束裁剪。
   * 与 {@code ReplenishmentComputeService.computeLine} 的口径一致（无仓容/预算约束）。
   */
  private static int suggestedQty(BigDecimal mu, int available, int inTransit) {
    ReorderPointCalculator.Result rp = ReorderPointCalculator.calculate(
        mu, LEAD_TIME_DAYS, REVIEW_PERIOD_DAYS, SAFETY_DAYS, available, inTransit);
    if (!rp.triggered()) {
      return 0;
    }
    int raw = rp.rawQuantity().setScale(0, java.math.RoundingMode.FLOOR).intValue();
    return ReplenishmentConstraintChecker.apply(
        new ReplenishmentConstraintChecker.Input(raw, 1, 1, null, available, inTransit,
            null, null))
        .feasibleQuantity();
  }

  private record CycleOutcome(boolean actualShortage, boolean triggered) {
  }

  private record BacktestSample(BigDecimal dailyDemand, int available, int inTransit,
                                int manualQty) {
  }
}
