package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存估值报表行。
 *
 * <p>按 SKU × 仓库给出数量与金额口径，供财务/运营对账与导出：
 * {@code stockValue} 为成本口径金额（Σ 均价 × 总库存），
 * {@code agingDays} 以最近入库时间倒推，用于呆滞识别。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class InventoryValuationRow {

  private String skuId;

  private String warehouseId;

  private String locationCode;

  private Integer totalStock;

  private Integer availableStock;

  private Integer lockedStock;

  private Integer damagedStock;

  private Integer safetyStock;

  private BigDecimal averageCost;

  /** 成本口径金额 = average_cost × total_stock */
  private BigDecimal stockValue;

  /** 距最近入库天数；无入库记录时为空 */
  private Long agingDays;

  /** 库存水位：NORMAL / LOW_STOCK / OUT_OF_STOCK */
  private String stockStatus;
}
