package com.cognora.platform.inventory.dto;

import lombok.Data;

/**
 * 安全库存设置请求。
 *
 * <p>安全库存是告警与库存水位判定的唯一阈值来源：{@code inv_alert} 的
 * LOW_STOCK/OUT_OF_STOCK 告警、列表的 {@code stockStatus} 与统计的
 * low/normal 计数都以 {@code available_stock <= safety_stock} 为准。
 * 历史上该值在初始化时被硬编码为 10 且没有任何修改入口，本接口用于补上
 * 这个配置缺口。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class InventorySafetyStockRequest {

  /**
   * 安全库存阈值，必须 >= 0（0 表示不启用低库存告警）。
   */
  private Integer safetyStock;

  /**
   * 最大库存（预警上限），可选；为空表示不修改。
   */
  private Integer maxStock;

  /**
   * 操作人 ID，可选，仅用于审计。
   */
  private String operatorId;

  /**
   * 备注，可选。
   */
  private String remark;
}
