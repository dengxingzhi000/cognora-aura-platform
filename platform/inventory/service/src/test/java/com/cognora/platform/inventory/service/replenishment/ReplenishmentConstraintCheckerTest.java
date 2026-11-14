package com.cognora.platform.inventory.service.replenishment;

import com.cognora.platform.inventory.domain.ReplenishmentFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 补货量约束裁剪用例：MOQ / 包装倍数 / 仓容 / 预算，以及冲突时必须给可行量+原因。
 */
class ReplenishmentConstraintCheckerTest {

  private static ReplenishmentConstraintChecker.Input input(
      int raw, int moq, int pack, Integer maxStock, int current, int onOrder,
      String unitPrice, String budget) {
    return new ReplenishmentConstraintChecker.Input(
        raw, moq, pack, maxStock, current, onOrder,
        unitPrice == null ? null : new BigDecimal(unitPrice),
        budget == null ? null : new BigDecimal(budget));
  }

  @Test
  @DisplayName("无约束时原样返回模型建议量")
  void passesThroughWhenNoConstraintApplies() {
    var r = ReplenishmentConstraintChecker.apply(input(100, 1, 1, null, 0, 0, null, null));

    assertThat(r.feasibleQuantity()).isEqualTo(100);
    assertThat(r.flags()).isEmpty();
    assertThat(r.reason()).contains("未命中约束");
  }

  @Test
  @DisplayName("MOQ 向上取整：需要 90，MOQ=100 → 100")
  void roundsUpToMinimumOrderQuantity() {
    var r = ReplenishmentConstraintChecker.apply(input(90, 100, 1, null, 0, 0, null, null));

    assertThat(r.feasibleQuantity()).isEqualTo(100);
    assertThat(r.flags()).contains(ReplenishmentFlags.ROUNDED_TO_MOQ);
    assertThat(r.reason()).contains("MOQ=100");
  }

  @Test
  @DisplayName("包装倍数向上取整：需要 95，包装 24 → 96")
  void roundsUpToPackMultiple() {
    var r = ReplenishmentConstraintChecker.apply(input(95, 1, 24, null, 0, 0, null, null));

    assertThat(r.feasibleQuantity()).isEqualTo(96);
    assertThat(r.flags()).contains(ReplenishmentFlags.ROUNDED_TO_MOQ);
  }

  @Test
  @DisplayName("MOQ 与包装倍数同时满足：步长取两者最小公倍数")
  void satisfiesBothMoqAndPackMultiple() {
    // MOQ=50、包装=30 → 步长 150；需要 100 → 150
    var r = ReplenishmentConstraintChecker.apply(input(100, 50, 30, null, 0, 0, null, null));

    assertThat(r.feasibleQuantity()).isEqualTo(150);
    assertThat(r.feasibleQuantity() % 50).isZero();
    assertThat(r.feasibleQuantity() % 30).isZero();
  }

  @Test
  @DisplayName("仓容上限裁剪：maxStock=120，现有 50，在途 20 → 最多 50")
  void capsByWarehouseCapacity() {
    var r = ReplenishmentConstraintChecker.apply(input(200, 1, 1, 120, 50, 20, null, null));

    assertThat(r.feasibleQuantity()).isEqualTo(50);
    assertThat(r.flags()).contains(ReplenishmentFlags.CAPPED_BY_CAPACITY);
    assertThat(r.reason()).contains("仓容上限");
  }

  @Test
  @DisplayName("仓容已满时可行量为 0，且不静默丢行")
  void returnsZeroWhenWarehouseIsFull() {
    // maxStock=100，现有 80，在途 20 → 剩余 0
    var r = ReplenishmentConstraintChecker.apply(input(50, 1, 1, 100, 80, 20, null, null));

    assertThat(r.feasibleQuantity()).isZero();
    assertThat(r.flags()).contains(ReplenishmentFlags.CAPPED_BY_CAPACITY);
    assertThat(r.reason()).contains("可行量为 0");
  }

  @Test
  @DisplayName("预算裁剪：单价 10、预算 350 → 最多 35 件")
  void capsByBudget() {
    var r = ReplenishmentConstraintChecker.apply(input(100, 1, 1, null, 0, 0, "10", "350"));

    assertThat(r.feasibleQuantity()).isEqualTo(35);
    assertThat(r.flags()).contains(ReplenishmentFlags.CAPPED_BY_BUDGET);
    assertThat(r.reason()).contains("预算上限");
  }

  @Test
  @DisplayName("预算为 0 时可行量为 0")
  void zeroBudgetYieldsZero() {
    var r = ReplenishmentConstraintChecker.apply(input(100, 1, 1, null, 0, 0, "10", "0"));

    assertThat(r.feasibleQuantity()).isZero();
    assertThat(r.flags()).contains(ReplenishmentFlags.CAPPED_BY_BUDGET);
  }

  @Test
  @DisplayName("预算不足以覆盖单价时按 0 件处理，不四舍五入成 1 件")
  void budgetBelowUnitPriceYieldsZero() {
    var r = ReplenishmentConstraintChecker.apply(input(100, 1, 1, null, 0, 0, "10", "9.99"));

    assertThat(r.feasibleQuantity()).isZero();
  }

  @Test
  @DisplayName("仓容与预算同时裁剪时两个标记都上报")
  void reportsBothCapacityAndBudgetFlags() {
    // maxStock 允许 50；预算 10 元/件 × 200 = 20 件 → 预算更紧
    var r = ReplenishmentConstraintChecker.apply(input(200, 1, 1, 120, 50, 20, "10", "200"));

    assertThat(r.feasibleQuantity()).isEqualTo(20);
    assertThat(r.flags()).contains(ReplenishmentFlags.CAPPED_BY_BUDGET);
  }

  @Test
  @DisplayName("取整会突破仓容时回退到不超过上限的最大合法倍数")
  void roundingNeverExceedsCapacityCeiling() {
    // 缺口 47，仓容上限 50，包装 24 → 向上取整 48 ≤ 50，允许（48 合法）
    var fits = ReplenishmentConstraintChecker.apply(input(47, 1, 24, 50, 0, 0, null, null));
    assertThat(fits.feasibleQuantity()).isEqualTo(48);
    assertThat(fits.feasibleQuantity()).isLessThanOrEqualTo(50);

    // 缺口 47，仓容上限 47，包装 24 → 向上取整 48 > 47，必须回退到 24 的倍数：24
    var fallback = ReplenishmentConstraintChecker.apply(input(47, 1, 24, 47, 0, 0, null, null));
    assertThat(fallback.feasibleQuantity()).isEqualTo(24);
    assertThat(fallback.feasibleQuantity()).isLessThanOrEqualTo(47);
    assertThat(fallback.reason()).contains("回退");
  }

  @Test
  @DisplayName("上限低于 MOQ 时本轮不补，而不是补一个供应商不接的量")
  void rejectsQuantityBelowMoqWhenCeilingCannotFitIt() {
    // 仓容上限 30，但 MOQ=50 → 任何合法量都放不下
    var r = ReplenishmentConstraintChecker.apply(input(200, 50, 1, 30, 0, 0, null, null));

    assertThat(r.feasibleQuantity()).isZero();
    assertThat(r.flags()).contains(ReplenishmentFlags.CAPPED_BY_CAPACITY);
  }

  @Test
  @DisplayName("缺口为 0 直接返回 0，不做取整")
  void zeroRawQuantityShortCircuits() {
    var r = ReplenishmentConstraintChecker.apply(input(0, 100, 24, null, 0, 0, null, null));

    assertThat(r.feasibleQuantity()).isZero();
    assertThat(r.flags()).isEmpty();
    assertThat(r.reason()).contains("无需补货");
  }

  @Test
  @DisplayName("取整辅助函数：roundUp/roundDown 的边界")
  void roundingHelpers() {
    assertThat(ReplenishmentConstraintChecker.roundUp(1, 1, 1)).isEqualTo(1);
    assertThat(ReplenishmentConstraintChecker.roundUp(24, 1, 24)).isEqualTo(24);
    assertThat(ReplenishmentConstraintChecker.roundUp(25, 1, 24)).isEqualTo(48);
    assertThat(ReplenishmentConstraintChecker.roundUp(10, 100, 1)).isEqualTo(100);
    assertThat(ReplenishmentConstraintChecker.roundUp(0, 100, 24)).isZero();

    assertThat(ReplenishmentConstraintChecker.roundDown(100, 1, 24)).isEqualTo(96);
    assertThat(ReplenishmentConstraintChecker.roundDown(47, 1, 24)).isEqualTo(24);
    assertThat(ReplenishmentConstraintChecker.roundDown(23, 1, 24)).isZero();
    assertThat(ReplenishmentConstraintChecker.roundDown(0, 1, 24)).isZero();
  }
}
