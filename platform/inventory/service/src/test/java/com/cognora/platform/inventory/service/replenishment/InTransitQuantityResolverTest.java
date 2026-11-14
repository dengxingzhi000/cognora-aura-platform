package com.cognora.platform.inventory.service.replenishment;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cognora.platform.inventory.domain.ReplenishmentStatus;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionItemMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 在途去重用例：这是「不重复下单」的核心正确性断言。
 *
 * <p>关键场景：同一批货既在建议单上（CONVERTED）又在采购单上（未收货），
 * 只能算一次，否则在途高估 → 该补的货被漏补。
 */
class InTransitQuantityResolverTest {

  private final InvReplenishmentSuggestionMapper suggestionMapper =
      mock(InvReplenishmentSuggestionMapper.class);
  private final InvReplenishmentSuggestionItemMapper itemMapper =
      mock(InvReplenishmentSuggestionItemMapper.class);

  private PurchaseInTransitPort purchasePort;
  private InTransitQuantityResolver resolver;

  @BeforeAll
  static void initLambdaCache() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, InvReplenishmentSuggestion.class);
    TableInfoHelper.initTableInfo(assistant, InvReplenishmentSuggestionItem.class);
  }

  private void givenSuggestion(String headId, ReplenishmentStatus status,
                               String skuId, String warehouseId, int qty) {
    InvReplenishmentSuggestion head = new InvReplenishmentSuggestion();
    head.setId(headId);
    head.setStatus(status.name());
    when(suggestionMapper.selectList(any())).thenReturn(List.of(head));

    InvReplenishmentSuggestionItem item = new InvReplenishmentSuggestionItem();
    item.setSuggestionId(headId);
    item.setSkuId(skuId);
    item.setWarehouseId(warehouseId);
    item.setSuggestedQty(qty);
    item.setAdoptedQty(qty);
    when(itemMapper.selectList(any())).thenReturn(List.of(item));
  }

  private void withPurchase(Map<String, Integer> purchase) {
    purchasePort = () -> purchase;
    resolver = new InTransitQuantityResolver(suggestionMapper, itemMapper, purchasePort);
  }

  @Test
  @DisplayName("ADOPTED 与采购在途相加：已采纳未转出的量采购侧看不到")
  void adoptedIsAddedToPurchaseInTransit() {
    givenSuggestion("s1", ReplenishmentStatus.ADOPTED, "sku-1", "wh-1", 40);
    withPurchase(Map.of("sku-1|wh-1", 100));

    assertThat(resolver.resolve(null).of("sku-1|wh-1")).isEqualTo(140);
  }

  @Test
  @DisplayName("CONVERTED 与采购在途不能相加 —— 同一批货只算一次，取采购侧")
  void convertedDoesNotDoubleCountPurchase() {
    givenSuggestion("s1", ReplenishmentStatus.CONVERTED, "sku-1", "wh-1", 40);
    withPurchase(Map.of("sku-1|wh-1", 100));

    // 不是 140：采购侧已在统计这 40 件（且会随收货递减）
    assertThat(resolver.resolve(null).of("sku-1|wh-1")).isEqualTo(100);
  }

  @Test
  @DisplayName("CONVERTED 但采购侧还没建单时仍计入，覆盖空窗期")
  void convertedCountsWhenPurchaseSideHasNothing() {
    givenSuggestion("s1", ReplenishmentStatus.CONVERTED, "sku-1", "wh-1", 40);
    withPurchase(Map.of());

    assertThat(resolver.resolve(null).of("sku-1|wh-1")).isEqualTo(40);
  }

  @Test
  @DisplayName("混合状态：ADOPTED 相加、CONVERTED 不重复")
  void mixedStatuses() {
    InvReplenishmentSuggestion adoptedHead = new InvReplenishmentSuggestion();
    adoptedHead.setId("s-adopt");
    adoptedHead.setStatus(ReplenishmentStatus.ADOPTED.name());
    InvReplenishmentSuggestion convertedHead = new InvReplenishmentSuggestion();
    convertedHead.setId("s-conv");
    convertedHead.setStatus(ReplenishmentStatus.CONVERTED.name());
    when(suggestionMapper.selectList(any())).thenReturn(List.of(adoptedHead, convertedHead));

    InvReplenishmentSuggestionItem a = new InvReplenishmentSuggestionItem();
    a.setSuggestionId("s-adopt");
    a.setSkuId("sku-1");
    a.setWarehouseId("wh-1");
    a.setSuggestedQty(20);
    a.setAdoptedQty(20);
    InvReplenishmentSuggestionItem c = new InvReplenishmentSuggestionItem();
    c.setSuggestionId("s-conv");
    c.setSkuId("sku-1");
    c.setWarehouseId("wh-1");
    c.setSuggestedQty(30);
    c.setAdoptedQty(30);
    when(itemMapper.selectList(any())).thenReturn(List.of(a, c));

    withPurchase(Map.of("sku-1|wh-1", 50));

    // 50（采购）+ 20（ADOPTED）；30（CONVERTED）已被采购侧覆盖
    assertThat(resolver.resolve(null).of("sku-1|wh-1")).isEqualTo(70);
  }

  @Test
  @DisplayName("采购侧不可用时退回全部建议在途（宁可高估也不重复下单）")
  void purchaseFailureFallsBackToAllSuggestions() {
    givenSuggestion("s1", ReplenishmentStatus.CONVERTED, "sku-1", "wh-1", 40);
    purchasePort = () -> {
      throw new IllegalStateException("scm-purchase unreachable");
    };
    resolver = new InTransitQuantityResolver(suggestionMapper, itemMapper, purchasePort);

    assertThat(resolver.resolve(null).of("sku-1|wh-1")).isEqualTo(40);
  }

  @Test
  @DisplayName("未采纳/已驳回的单据不计入在途")
  void draftAndRejectedAreNotInTransit() {
    withPurchase(Map.of());
    when(suggestionMapper.selectList(any())).thenReturn(List.of());
    when(itemMapper.selectList(any())).thenReturn(List.of());

    assertThat(resolver.resolve(null).isEmpty()).isTrue();
  }

  @Test
  @DisplayName("缺失 SKU 或仓的行无法组键，被跳过")
  void rowsWithoutKeyAreSkipped() {
    givenSuggestion("s1", ReplenishmentStatus.ADOPTED, null, "wh-1", 40);
    withPurchase(Map.of());

    assertThat(resolver.resolve(null).isEmpty()).isTrue();
  }

  @Test
  @DisplayName("排除本周期正在生成的建议单，避免自我计入")
  void excludesCurrentBusinessKey() {
    withPurchase(Map.of());
    when(suggestionMapper.selectList(any())).thenReturn(List.of());
    when(itemMapper.selectList(any())).thenReturn(List.of());

    resolver.resolve("tenant|wh-1|2026-W45");
    // 只断言不抛错且走通；排除条件体现在 SQL wrapper，由集成测试覆盖
    assertThat(resolver.resolve("tenant|wh-1|2026-W45").isEmpty()).isTrue();
  }
}
