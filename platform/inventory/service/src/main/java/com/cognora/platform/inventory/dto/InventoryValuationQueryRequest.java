package com.cognora.platform.inventory.dto;

import lombok.Data;

/**
 * 库存估值查询请求。
 *
 * <p>复用台账查询的过滤口径（SKU/仓库/库位/水位），只增加「呆滞天数」阈值。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class InventoryValuationQueryRequest {

  private String skuId;

  private String warehouseId;

  private String locationCode;

  /** NORMAL / LOW_STOCK / OUT_OF_STOCK */
  private String stockStatus;

  /**
   * 呆滞阈值（天）：最近入库早于 today - agingDays 视为呆滞。
   * 为空则不计算呆滞。
   */
  private Integer agingDays;

  @jakarta.validation.constraints.Min(value = 1, message = "页码最小为 1")
  private Integer page = 1;

  @jakarta.validation.constraints.Min(value = 1, message = "每页数量最小为 1")
  @jakarta.validation.constraints.Max(value = 500, message = "每页数量最大为 500")
  private Integer size = 20;
}
