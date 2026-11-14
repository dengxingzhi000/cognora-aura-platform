package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建盘点单请求。
 *
 * <p>创建即冻结账面数：明细的 book_* 取自创建时点的台账，因此
 * {@code locationCode} 为空表示按整仓盘点。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class StocktakeCreateRequest {

  @jakarta.validation.constraints.NotBlank(message = "仓库 ID 不能为空")
  private String warehouseId;

  /** 可选：限定库位盘点；为空表示整仓 */
  private String locationCode;

  /** 可选：只盘点这些 SKU；为空表示该范围内全部台账行 */
  private java.util.List<String> skuIds;

  private String operatorId;

  private String remark;
}
