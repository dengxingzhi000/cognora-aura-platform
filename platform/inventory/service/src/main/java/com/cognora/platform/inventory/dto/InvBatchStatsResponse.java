package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 批次/效期报表汇总。
 *
 * <p>与分页行互补：列表回答“哪些批次有问题”，汇总回答“问题有多大”，
 * 用于报表页顶部的 KPI 与导出时的合计行。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class InvBatchStatsResponse {

  /** 匹配的批次总数 */
  private Long totalBatches;

  /** 已过期批次数量 */
  private Long expiredBatches;

  /** 临期（未过期且在观察窗口内）批次数量 */
  private Long nearExpiryBatches;

  /** 已耗尽批次数量 */
  private Long depletedBatches;

  /** 仍可用的总数量 */
  private Long availableQuantity;

  /** 仍可用的总金额 */
  private BigDecimal availableValue;

  /** 已过期但仍占库存的数量（需要处置） */
  private Long expiredQuantity;

  /** 已过期但仍占库存的金额 */
  private BigDecimal expiredValue;
}
