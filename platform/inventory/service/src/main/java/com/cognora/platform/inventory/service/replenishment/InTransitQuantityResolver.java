package com.cognora.platform.inventory.service.replenishment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.inventory.domain.ReplenishmentStatus;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionItemMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在途量解析：建议在途（本服务内）+ 采购在途（跨服务，见 {@link PurchaseInTransitPort}）。
 *
 * <p>「建议在途」= 本租户处于 ADOPTED（已采纳待转）或 CONVERTED（已转出但货未到）
 * 状态的建议单里，每个 SKU×仓 的采纳量合计。CONVERTED 也计入：单据转出去之后
 * 采购单可能还没建起来，这段时间货同样在路上，漏算就会在下一个复核周期重复补。
 *
 * <p>数据源是本库，因此用 {@code @Slave} 只读查询；采购在途走
 * {@link PurchaseInTransitPort}，其不可用时降级为「已知的建议在途」并记录告警——
 * 宁可少算在途（触发补货）也不要让整个计算失败。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InTransitQuantityResolver {

  private final InvReplenishmentSuggestionMapper suggestionMapper;
  private final InvReplenishmentSuggestionItemMapper itemMapper;
  private final PurchaseInTransitPort purchaseInTransitPort;

  /**
   * 解析在途量。
   *
   * <p><strong>去重口径（重要）：</strong>两段在途不能直接相加，否则会重复计算：
   * <ul>
   *   <li>{@code ADOPTED}：已采纳但还没转出，采购侧看不到，必须由本服务计入；</li>
   *   <li>{@code CONVERTED}：已经转成采购计划，采购侧一旦建单并审批，
   *       同一个 SKU×仓 会同时出现在两段里。此时<strong>以采购侧为准</strong>——
   *       采购侧的数字来自 {@code pur_order_item} 的实际未收量（会随收货递减），
   *       而建议单上的数量是快照，永远不会变。若两边相加，在途被高估，
   *       该补的货会被漏补。</li>
   * </ul>
   * 因此 {@code CONVERTED} 只在采购侧<strong>没有</strong>该键时计入（覆盖
   * 「已转出但采购单还没建起来」的空窗期）。
   *
   * @param excludeBusinessKey 需要排除的建议单幂等键（一般是本周期正在生成的单），
   *                           避免把「正在算的这一单」算进在途
   */
  @Slave
  public InTransitQuantities resolve(String excludeBusinessKey) {
    Map<String, Integer> adopted = new HashMap<>();
    Map<String, Integer> converted = new HashMap<>();
    splitSuggestionInTransit(excludeBusinessKey, adopted, converted);

    Map<String, Integer> purchase;
    try {
      purchase = purchaseInTransitPort.unreceivedQuantities();
    } catch (RuntimeException e) {
      // 采购侧不可用：退回「全部建议在途」，此时 CONVERTED 也计入，
      // 宁可高估在途（少补）也不要在货还在路上时重复下单。
      log.warn("采购在途量查询失败，本轮按全部建议在途去重（含已转出）: {}", e.getMessage());
      Map<String, Integer> merged = new HashMap<>(adopted);
      converted.forEach((key, qty) -> merged.merge(key, qty, Integer::sum));
      return InTransitQuantities.of(merged);
    }

    Map<String, Integer> merged = new HashMap<>(purchase);
    // ADOPTED 一定不在采购侧，直接加
    adopted.forEach((key, qty) -> merged.merge(key, qty, Integer::sum));
    // CONVERTED 只在采购侧没有该键时才加：同一批货已在 pur_order_item 里统计过
    converted.forEach((key, qty) -> merged.putIfAbsent(key, qty));
    return InTransitQuantities.of(merged);
  }

  /**
   * 把建议在途按状态拆成两段。
   *
   * @param adopted   已采纳未转出的量（采购侧不可见）
   * @param converted 已转出的量（采购侧可能已可见）
   */
  @Slave
  void splitSuggestionInTransit(String excludeBusinessKey,
                                Map<String, Integer> adopted,
                                Map<String, Integer> converted) {
    List<InvReplenishmentSuggestion> heads = suggestionMapper.selectList(
        new LambdaQueryWrapper<InvReplenishmentSuggestion>()
            .in(InvReplenishmentSuggestion::getStatus,
                ReplenishmentStatus.ADOPTED.name(), ReplenishmentStatus.CONVERTED.name())
            .ne(excludeBusinessKey != null && !excludeBusinessKey.isBlank(),
                InvReplenishmentSuggestion::getBusinessKey, excludeBusinessKey));
    if (heads.isEmpty()) {
      return;
    }

    Map<String, String> statusByHeadId = new HashMap<>();
    for (InvReplenishmentSuggestion head : heads) {
      statusByHeadId.put(head.getId(), head.getStatus());
    }

    List<InvReplenishmentSuggestionItem> items = itemMapper.selectList(
        new LambdaQueryWrapper<InvReplenishmentSuggestionItem>()
            .in(InvReplenishmentSuggestionItem::getSuggestionId, statusByHeadId.keySet()));
    for (InvReplenishmentSuggestionItem item : items) {
      String key = InTransitQuantities.key(item.getSkuId(), item.getWarehouseId());
      if (key == null) {
        continue;
      }
      boolean isConverted = ReplenishmentStatus.CONVERTED.name()
          .equals(statusByHeadId.get(item.getSuggestionId()));
      (isConverted ? converted : adopted).merge(key, item.effectiveQty(), Integer::sum);
    }
  }

  /** 仅本服务内的「已采纳/已转出但未收货」建议量（不区分状态，供只读展示）。 */
  @Slave
  public Map<String, Integer> suggestionInTransit(String excludeBusinessKey) {
    Map<String, Integer> adopted = new HashMap<>();
    Map<String, Integer> converted = new HashMap<>();
    splitSuggestionInTransit(excludeBusinessKey, adopted, converted);
    converted.forEach((key, qty) -> adopted.merge(key, qty, Integer::sum));
    return adopted;
  }
}
