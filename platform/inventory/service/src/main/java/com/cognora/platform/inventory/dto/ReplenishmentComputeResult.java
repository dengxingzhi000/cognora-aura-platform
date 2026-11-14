package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.util.List;

/**
 * 补货计算结果（一次计算或 dry-run 的返回）。
 *
 * <p>与建议单响应分开是有意的：dry-run 不落库时没有单号与明细 ID，
 * 但仍然需要把每一行算出来的量与原因展示出来。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentComputeResult {

  /** 补货周期 */
  private String period;

  /** 参与计算的台账行数 */
  private int scannedInventories;

  /** 触发补货的行数（裁剪后仍 &gt; 0） */
  private int triggeredItems;

  /** 被约束裁到 0 或未触发的行数 */
  private int skippedItems;

  /** 冷启动/缺策略等原因需要人工确认的行数 */
  private int needsManualItems;

  /** 汇总建议量 */
  private long totalSuggestedQty;

  /** 幂等键：tenant|warehouse|period */
  private String businessKey;

  /** 已存在的建议单（重复触发时返回），为空表示本次新建 */
  private String existingSuggestionId;

  /** 是否 dry-run（未落库） */
  private boolean dryRun;

  private List<Line> lines;

  /** 单行计算结果，供人审解释。 */
  @Data
  public static class Line {
    private String skuId;
    private String warehouseId;
    private int availableStock;
    private int inTransitQty;
    private java.math.BigDecimal reorderPoint;
    private java.math.BigDecimal safetyStock;
    private java.math.BigDecimal targetStock;
    private int suggestedQty;
    private String flags;
    private String reason;
  }
}
