package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存估值汇总。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class InventoryValuationStats {

  /** 参与统计的台账行数 */
  private Long rowCount;

  private Long totalStockQuantity;

  private Long availableStockQuantity;

  private Long lockedStockQuantity;

  private Long damagedStockQuantity;

  /** 成本口径总金额 */
  private BigDecimal totalStockValue;

  /** 呆滞（超过 agingDays 无入库）行数 */
  private Long slowMovingCount;

  /** 呆滞行占用金额 */
  private BigDecimal slowMovingValue;
}
