package com.cognora.platform.inventory.dto;

import lombok.Data;

/**
 * 盘点单据分页查询请求。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class StocktakeQueryRequest {

  private String warehouseId;

  /** DRAFT / SUBMITTED / APPROVED / POSTED / REJECTED / CANCELLED */
  private String status;

  /** 盘点单号，支持模糊匹配 */
  private String stocktakeNo;

  @jakarta.validation.constraints.Min(value = 1, message = "页码最小为 1")
  private Integer page = 1;

  @jakarta.validation.constraints.Min(value = 1, message = "每页数量最小为 1")
  @jakarta.validation.constraints.Max(value = 500, message = "每页数量最大为 500")
  private Integer size = 20;
}
