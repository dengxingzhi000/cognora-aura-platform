package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 批次/效期报表查询请求。
 *
 * <p>只读查询：按仓库、SKU、批次号、质量状态、批次状态与效期区间筛选，
 * 用于临期/过期/呆滞批次的运营盘点。{@code expiryTo} 为空时默认取
 * “今天 + withinDays 天”，便于“即将到期”这类一线最常用的视图。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class InvBatchQueryRequest {

  private String skuId;

  private String warehouseId;

  /** 批次号，支持模糊匹配 */
  private String batchCode;

  /** 质量状态：1-合格 2-待检 3-不合格 4-隔离 */
  private Integer qualityStatus;

  /** 批次状态：0-已耗尽 1-正常 2-临期 3-已过期 */
  private Integer status;

  /** 效期区间（含） */
  private LocalDate expiryFrom;

  private LocalDate expiryTo;

  /**
   * 便捷参数：当 {@code expiryTo} 未指定时，用它表示“今天起 N 天内到期”。
   * 为空则不施加效期上限。
   */
  private Integer withinDays;

  /** 是否只看仍有可用量的批次 */
  private Boolean hasAvailable;

  @jakarta.validation.constraints.Min(value = 1, message = "页码最小为 1")
  private Integer page = 1;

  @jakarta.validation.constraints.Min(value = 1, message = "每页数量最小为 1")
  @jakarta.validation.constraints.Max(value = 500, message = "每页数量最大为 500")
  private Integer size = 20;
}
