package com.cognora.platform.inventory.service.replenishment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 再订货点公式用例：每个分支都要能被手算复核。
 *
 * <p>手算基准（贯穿全文）：μ=10 件/天，L=7 天，复核周期 7 天，safetyDays=3 天
 * <ul>
 *   <li>SS = 10 × 3 = 30</li>
 *   <li>ROP = 10 × 7 + 30 = 100</li>
 *   <li>targetStock = 10 × (7+7) + 30 = 170</li>
 * </ul>
 */
class ReorderPointCalculatorTest {

  private static final BigDecimal MU_10 = new BigDecimal("10");
  private static final BigDecimal SAFETY_3 = new BigDecimal("3");

  @Test
  @DisplayName("典型触发：可用 50 + 在途 20 = 70 ≤ ROP 100，建议 170−70 = 100")
  void triggersWhenStockPlusInTransitIsBelowReorderPoint() {
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        MU_10, 7, 7, SAFETY_3, 50, 20);

    assertThat(r.safetyStock()).isEqualByComparingTo("30");
    assertThat(r.reorderPoint()).isEqualByComparingTo("100");
    assertThat(r.targetStock()).isEqualByComparingTo("170");
    assertThat(r.rawQuantity()).isEqualByComparingTo("100");
    assertThat(r.suggestedQuantity()).isEqualTo(100);
    assertThat(r.triggered()).isTrue();
    assertThat(r.reason()).contains("触发补货");
  }

  @Test
  @DisplayName("库存充足时不触发：可用 200 > ROP 100")
  void doesNotTriggerWhenStockIsSufficient() {
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        MU_10, 7, 7, SAFETY_3, 200, 0);

    assertThat(r.triggered()).isFalse();
    assertThat(r.suggestedQuantity()).isZero();
    assertThat(r.reason()).contains("库存充足");
  }

  @Test
  @DisplayName("在途抬高库存后不再触发：可用 30 + 在途 80 = 110 > ROP 100")
  void inTransitRaisesStockAboveReorderPoint() {
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        MU_10, 7, 7, SAFETY_3, 30, 80);

    assertThat(r.triggered()).isFalse();
    assertThat(r.suggestedQuantity()).isZero();
  }

  @Test
  @DisplayName("在途已覆盖缺口：可用 60 + 在途 110 = 170 = 目标水位，Q_raw=0 不补")
  void inTransitCoversTheGap() {
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        MU_10, 7, 7, SAFETY_3, 60, 110);

    assertThat(r.rawQuantity()).isEqualByComparingTo("0");
    assertThat(r.triggered()).isFalse();
    assertThat(r.suggestedQuantity()).isZero();
  }

  @Test
  @DisplayName("Q_raw ≤ 0 时不补，即使触发了 ROP 判定")
  void doesNotOrderWhenRawQuantityIsNotPositive() {
    // 可用 40 + 在途 130 = 170 = targetStock；ROP=100，170 > 100 也不触发
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        MU_10, 7, 7, SAFETY_3, 40, 130);

    assertThat(r.rawQuantity()).isEqualByComparingTo("0");
    assertThat(r.suggestedQuantity()).isZero();
    assertThat(r.triggered()).isFalse();
  }

  @Test
  @DisplayName("边界：库存恰好等于 ROP 时触发（≤ 而非 <）")
  void triggersAtExactlyTheReorderPoint() {
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        MU_10, 7, 7, SAFETY_3, 100, 0);

    assertThat(r.triggered()).isTrue();
    assertThat(r.suggestedQuantity()).isEqualTo(70);
  }

  @Test
  @DisplayName("零需求不触发")
  void zeroDemandNeverTriggers() {
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        BigDecimal.ZERO, 7, 7, SAFETY_3, 0, 0);

    assertThat(r.reorderPoint()).isEqualByComparingTo("0");
    assertThat(r.rawQuantity()).isEqualByComparingTo("0");
    assertThat(r.triggered()).isFalse();
  }

  @Test
  @DisplayName("小数缺口向下取整：不足一件不补成一件")
  void floorsFractionalQuantity() {
    // μ=0.35/天 → SS=1.05，ROP=0.35×7+1.05=3.5，target=0.35×14+1.05=5.95
    // 可用 0 → Q_raw=5.95 → 向下取整 5
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        new BigDecimal("0.35"), 7, 7, SAFETY_3, 0, 0);

    assertThat(r.targetStock()).isEqualByComparingTo("5.95");
    assertThat(r.rawQuantity()).isEqualByComparingTo("5.95");
    assertThat(r.suggestedQuantity()).isEqualTo(5);
  }

  @Test
  @DisplayName("交期为 0 时 ROP 只剩安全库存")
  void zeroLeadTimeLeavesOnlySafetyStock() {
    ReorderPointCalculator.Result r = ReorderPointCalculator.calculate(
        MU_10, 0, 7, SAFETY_3, 0, 0);

    assertThat(r.reorderPoint()).isEqualByComparingTo("30");
    assertThat(r.targetStock()).isEqualByComparingTo("100");
    assertThat(r.suggestedQuantity()).isEqualTo(100);
  }

  @Test
  @DisplayName("负数入参直接失败，而不是算出负数补货量")
  void rejectsNegativeInputs() {
    assertThatThrownBy(() -> ReorderPointCalculator.calculate(MU_10, -1, 7, SAFETY_3, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("交期");
    assertThatThrownBy(() -> ReorderPointCalculator.calculate(MU_10, 7, -1, SAFETY_3, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("复核周期");
    assertThatThrownBy(() -> ReorderPointCalculator.calculate(null, 7, 7, SAFETY_3, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("日需求");
    assertThatThrownBy(() -> ReorderPointCalculator.calculate(MU_10, 7, 7, null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("安全库存天数");
    assertThatThrownBy(() -> ReorderPointCalculator.calculate(MU_10, 7, 7, SAFETY_3, -1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("可用库存");
    assertThatThrownBy(() -> ReorderPointCalculator.calculate(MU_10, 7, 7, SAFETY_3, 0, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("在途量");
  }
}
