package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 需求聚合的日粒度投影（SKU × 仓 × 天 → 销售出库量）。
 *
 * <p>由 {@code InvDemandAggregationMapper} 在 SQL 侧分组产生，直接对应
 * {@link com.cognora.platform.inventory.service.query.DemandAggregationQueryService}
 * 加权移动平均的输入序列。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class DemandDailyAggregate {

  private String skuId;

  private String warehouseId;

  /** 出库发生的自然日 */
  private LocalDate demandDate;

  /** 当日销售出库量（正数） */
  private BigDecimal demandQuantity;
}
