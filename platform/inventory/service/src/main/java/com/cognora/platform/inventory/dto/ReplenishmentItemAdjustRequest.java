package com.cognora.platform.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 人审改量/换供应商请求（仅 DRAFT 可改）。
 *
 * <p>{@code adoptedQty} 与 {@code supplierId} 都可以单独改：改量不改供应商是常见操作，
 * 反之亦然。两者都为 null 时视为无操作，直接返回当前明细。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentItemAdjustRequest {

  /** 人工采纳量；null 表示不改量。允许改成 0（本轮不补该 SKU） */
  @Min(value = 0, message = "采纳数量不能为负数")
  private Integer adoptedQty;

  /** 换供应商；null 表示不换 */
  private String supplierId;

  /** 改量原因，用于反馈闭环 */
  private String reason;

  private String operatorId;
}
