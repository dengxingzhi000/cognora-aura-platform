package com.cognora.scm.purchase.domain.dto;

import lombok.Data;

/**
 * 采购在途量聚合行：按 SKU×仓 汇总「已下单未收完」的数量。
 *
 * <p>供 platform-inventory 的补货计算通过内部端点读取，避免库存服务跨库直查
 * {@code pur_order_item}（违反 CQRS 边界）。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class PurchaseInTransitRow {

  /** SKU ID；采购明细可能只有 material 口径，此时该列为空 */
  private String skuId;

  private String warehouseId;

  /** 未收货数量 = sum(quantity - received_quantity) */
  private Integer inTransitQty;
}
