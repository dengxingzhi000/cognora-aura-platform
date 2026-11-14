package com.cognora.platform.inventory.service.replenishment;

import java.util.HashMap;
import java.util.Map;

/**
 * 在途量解析结果（按 SKU×仓）。
 *
 * <p>设计稿 §4.2 强调「inTransit 必须算，否则重复补货」。在途由两部分组成：
 * <ol>
 *   <li><b>采购在途</b>：已下采购单但尚未收货的量（来自 scm-purchase）；</li>
 *   <li><b>建议在途</b>：本周期已经采纳（ADOPTED / CONVERTED）但还没变成采购单的
 *       建议量——这一部分只有库存服务自己知道，不能指望采购侧。</li>
 * </ol>
 * 两者分开记录，是为了在建议单上能解释「这个 SKU 明明缺货为什么不补」：
 * 是因为货在路上，还是因为上一张建议单还没转完。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public final class InTransitQuantities {

  private final Map<String, Integer> quantities;

  private InTransitQuantities(Map<String, Integer> quantities) {
    this.quantities = quantities;
  }

  public static InTransitQuantities empty() {
    return new InTransitQuantities(Map.of());
  }

  public static InTransitQuantities of(Map<String, Integer> quantities) {
    return new InTransitQuantities(
        quantities == null ? Map.of() : new HashMap<>(quantities));
  }

  /** 该 key 的在途量；缺失视为 0。 */
  public int of(String key) {
    Integer value = quantities.get(key);
    return value == null ? 0 : Math.max(0, value);
  }

  public boolean isEmpty() {
    return quantities.isEmpty();
  }

  public Map<String, Integer> asMap() {
    return Map.copyOf(quantities);
  }

  /** SKU×仓 复合键；任一侧为空则返回 null（该行无法做在途去重）。 */
  public static String key(String skuId, String warehouseId) {
    if (skuId == null || skuId.isBlank() || warehouseId == null || warehouseId.isBlank()) {
      return null;
    }
    return skuId + "|" + warehouseId;
  }
}
