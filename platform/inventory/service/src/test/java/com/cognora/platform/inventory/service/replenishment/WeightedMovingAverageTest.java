package com.cognora.platform.inventory.service.replenishment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加权移动平均用例：权重、缺天补 0、冷启动标记。
 */
class WeightedMovingAverageTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  @DisplayName("线性递增权重：2 天序列 [10, 20] → (10×1 + 20×2)/3 = 16.6667")
  void weightsRecentDaysMore() {
    var r = WeightedMovingAverage.calculate(List.of(d("10"), d("20")), 1);

    assertThat(r.dailyDemand()).isEqualByComparingTo("16.6667");
    assertThat(r.sampleDays()).isEqualTo(2);
    assertThat(r.lookbackDays()).isEqualTo(2);
  }

  @Test
  @DisplayName("等量需求时加权平均等于该常量")
  void constantSeriesAveragesToItself() {
    var r = WeightedMovingAverage.calculate(
        List.of(d("5"), d("5"), d("5"), d("5")), 1);

    assertThat(r.dailyDemand()).isEqualByComparingTo("5");
  }

  @Test
  @DisplayName("近期上升趋势会把 μ 拉向近期值")
  void uptrendPullsAverageUpward() {
    var rising = WeightedMovingAverage.calculate(
        List.of(d("1"), d("1"), d("1"), d("10")), 1);
    var flat = WeightedMovingAverage.calculate(
        List.of(d("1"), d("1"), d("1"), d("1")), 1);

    assertThat(rising.dailyDemand()).isGreaterThan(flat.dailyDemand());
    // (1+2+3+40)/10 = 4.6
    assertThat(rising.dailyDemand()).isEqualByComparingTo("4.6");
  }

  @Test
  @DisplayName("全 0 序列 μ=0，且不需要人工确认之外的特殊处理")
  void allZeroSeries() {
    var r = WeightedMovingAverage.calculate(List.of(d("0"), d("0")), 1);

    assertThat(r.dailyDemand()).isEqualByComparingTo("0");
    assertThat(r.sampleDays()).isZero();
    assertThat(r.lowConfidence()).isTrue();
  }

  @Test
  @DisplayName("空序列返回 0 并标记低置信")
  void emptySeriesIsLowConfidence() {
    var r = WeightedMovingAverage.calculate(List.of(), 7);

    assertThat(r.dailyDemand()).isEqualByComparingTo("0");
    assertThat(r.sampleDays()).isZero();
    assertThat(r.lookbackDays()).isZero();
    assertThat(r.lowConfidence()).isTrue();
  }

  @Test
  @DisplayName("冷启动：有出库天数少于阈值 → LOW_CONFIDENCE")
  void coldStartIsFlaggedLowConfidence() {
    var r = WeightedMovingAverage.calculate(
        List.of(d("3"), d("0"), d("0"), d("0"), d("0"), d("0")), 7);

    assertThat(r.sampleDays()).isEqualTo(1);
    assertThat(r.lowConfidence()).isTrue();
  }

  @Test
  @DisplayName("样本达标 → 正常置信度")
  void enoughSamplesIsNormalConfidence() {
    var r = WeightedMovingAverage.calculate(
        List.of(d("1"), d("1"), d("1"), d("1"), d("1"), d("1"), d("1")), 7);

    assertThat(r.sampleDays()).isEqualTo(7);
    assertThat(r.lowConfidence()).isFalse();
  }

  @Test
  @DisplayName("null 元素按 0 处理，不抛 NPE")
  void nullEntriesCountAsZero() {
    var r = WeightedMovingAverage.calculate(
        java.util.Arrays.asList(d("10"), null), 1);

    // (10×1 + 0×2)/3 = 3.3333
    assertThat(r.dailyDemand()).isEqualByComparingTo("3.3333");
    assertThat(r.sampleDays()).isEqualTo(1);
  }

  @Test
  @DisplayName("负数出库量直接失败，而不是算出负需求")
  void rejectsNegativeQuantities() {
    assertThatThrownBy(() -> WeightedMovingAverage.calculate(List.of(d("-1")), 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为负数");
  }

  // -------------------------------------------------------------- densify

  @Test
  @DisplayName("densify 把缺失的天补成 0，并保持日期升序长度")
  void densifyFillsMissingDaysWithZero() {
    Map<LocalDate, BigDecimal> sparse = new LinkedHashMap<>();
    sparse.put(LocalDate.of(2026, 11, 1), d("5"));
    sparse.put(LocalDate.of(2026, 11, 3), d("7"));

    List<BigDecimal> dense = WeightedMovingAverage.densify(
        sparse, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

    assertThat(dense).hasSize(3);
    assertThat(dense.get(0)).isEqualByComparingTo("5");
    assertThat(dense.get(1)).isEqualByComparingTo("0");
    assertThat(dense.get(2)).isEqualByComparingTo("7");
  }

  @Test
  @DisplayName("densify：只有单日时长度为 1")
  void densifySingleDay() {
    Map<LocalDate, BigDecimal> sparse = new LinkedHashMap<>();
    sparse.put(LocalDate.of(2026, 11, 1), d("2"));

    List<BigDecimal> dense = WeightedMovingAverage.densify(
        sparse, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));

    assertThat(dense).containsExactly(d("2"));
  }

  @Test
  @DisplayName("densify：区间反了或为空时返回空列表")
  void densifyRejectsInvertedRange() {
    assertThat(WeightedMovingAverage.densify(
        Map.of(), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1))).isEmpty();
    assertThat(WeightedMovingAverage.densify(
        Map.of(), null, LocalDate.of(2026, 11, 1))).isEmpty();
  }

  @Test
  @DisplayName("缺天按 0 计入会让稀疏商品的 μ 明显低于只对有出库天求平均")
  void densifyLowersAverageForSparseItems() {
    // 4 天里只有第 1 天卖了 40
    Map<LocalDate, BigDecimal> sparse = new LinkedHashMap<>();
    sparse.put(LocalDate.of(2026, 11, 1), d("40"));
    List<BigDecimal> dense = WeightedMovingAverage.densify(
        sparse, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 4));

    var r = WeightedMovingAverage.calculate(dense, 1);
    // (40×1 + 0 + 0 + 0) / (1+2+3+4) = 4
    assertThat(r.dailyDemand()).isEqualByComparingTo("4");
    // 只对有出库的天求均值会得到 40，虚高 10 倍
    assertThat(r.dailyDemand()).isLessThan(d("40"));
  }
}
