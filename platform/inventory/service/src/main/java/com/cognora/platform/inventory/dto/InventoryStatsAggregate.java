package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存统计的数据库聚合投影。
 *
 * <p>由单条 {@code SELECT SUM/COUNT ... GROUP BY} 直接产出，避免把整张
 * {@code inv_inventory} 拉进 JVM 做流式聚合。字段为原始聚合值，
 * 面向接口的比率/派生值在服务层组装。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class InventoryStatsAggregate {

  /** 台账行数（SKU × 仓库） */
  private Long rowCount;

  /** 不同 SKU 数 */
  private Long skuCount;

  /** 不同仓库数 */
  private Long warehouseCount;

  /** 总库存数量 */
  private Long totalStockQuantity;

  /** 可用库存数量 */
  private Long availableStockQuantity;

  /** 锁定库存数量 */
  private Long lockedStockQuantity;

  /** 损坏库存数量 */
  private Long damagedStockQuantity;

  /** 总库存金额（Σ average_cost × total_stock） */
  private BigDecimal totalStockValue;

  /** 缺货行数（available_stock = 0） */
  private Long outOfStockCount;

  /** 低库存行数（0 < available_stock <= safety_stock） */
  private Long lowStockCount;

  /** 正常行数（available_stock > safety_stock） */
  private Long normalStockCount;
}
