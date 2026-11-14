package com.cognora.platform.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 补货建议单分页查询请求。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentSuggestionQueryRequest {

  private String warehouseId;

  /** DRAFT / ADOPTED / CONVERTED / REJECTED / CANCELLED */
  private String status;

  /** 补货周期，如 2026-W45 */
  private String period;

  /** 建议单号，模糊匹配 */
  private String suggestionNo;

  @Min(value = 1, message = "页码最小为 1")
  private Integer page = 1;

  @Min(value = 1, message = "每页数量最小为 1")
  @Max(value = 200, message = "每页数量最大为 200")
  private Integer size = 20;
}
