package com.cognora.platform.inventory.service.replenishment;

import java.util.Map;

/**
 * 采购在途量的只读端口。
 *
 * <p>所属域是 {@code scm-purchase}（{@code pur_order}/{@code pur_order_item}），
 * 库存服务不能直接查它的库（违反 CQRS 边界），因此抽象成端口：
 * <ul>
 *   <li>生产实现 {@link HttpPurchaseInTransitClient}：经 Nacos 服务发现调用
 *       采购服务的只读指标端点；</li>
 *   <li>单测与本地无采购服务时用 {@link Empty}（返回空表，即「没有已知在途」）。</li>
 * </ul>
 *
 * <p>返回值按 {@code skuId|warehouseId} 聚合，与本服务的
 * {@link InTransitQuantities#key} 保持同一口径。实现必须只读、超时可控，
 * 并且不得阻塞补货计算太久（调用方已对异常做降级）。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public interface PurchaseInTransitPort {

  /**
   * 查询尚未收货的采购在途量。
   *
   * @return key = {@code skuId|warehouseId}，value = 未收货件数（&ge;0）
   * @throws RuntimeException 下游不可用时抛出，由调用方降级处理
   */
  Map<String, Integer> unreceivedQuantities();

  /**
   * 无操作实现：视作「没有已知在途」。
   *
   * <p>刻意不缓存也不返回其它区的数据：在途算错的代价是重复补货，宁可承认不知道。
   */
  final class Empty implements PurchaseInTransitPort {

    @Override
    public Map<String, Integer> unreceivedQuantities() {
      return Map.of();
    }
  }
}
