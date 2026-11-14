package com.cognora.platform.inventory.service.command;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.inventory.domain.entity.InvDemandForecast;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentPolicy;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import com.cognora.platform.inventory.domain.entity.Inventory;
import com.cognora.platform.inventory.dto.ReplenishmentComputeResult;
import com.cognora.platform.inventory.dto.ReplenishmentGenerateRequest;
import com.cognora.platform.inventory.mapper.InvDemandForecastMapper;
import com.cognora.platform.inventory.mapper.InvInventoryMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentPolicyMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionItemMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionMapper;
import com.cognora.platform.inventory.service.query.DemandAggregationQueryService;
import com.cognora.platform.inventory.service.replenishment.InTransitQuantities;
import com.cognora.platform.inventory.service.replenishment.InTransitQuantityResolver;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补货计算编排用例：幂等、在途去重、缺策略时的降级与人审标记。
 */
class ReplenishmentComputeServiceTest {

  private static final UUID TENANT = UUID.fromString("019a0aee-0000-7000-8000-000000000002");

  private final InvInventoryMapper inventoryMapper = mock(InvInventoryMapper.class);
  private final InvReplenishmentPolicyMapper policyMapper = mock(InvReplenishmentPolicyMapper.class);
  private final InvDemandForecastMapper forecastMapper = mock(InvDemandForecastMapper.class);
  private final InvReplenishmentSuggestionMapper suggestionMapper =
      mock(InvReplenishmentSuggestionMapper.class);
  private final InvReplenishmentSuggestionItemMapper itemMapper =
      mock(InvReplenishmentSuggestionItemMapper.class);
  private final DemandAggregationQueryService demandAggregation =
      mock(DemandAggregationQueryService.class);
  private final InTransitQuantityResolver inTransitResolver =
      mock(InTransitQuantityResolver.class);

  private ReplenishmentComputeService service;

  @BeforeAll
  static void initLambdaCache() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, InvReplenishmentSuggestion.class);
    TableInfoHelper.initTableInfo(assistant, InvReplenishmentSuggestionItem.class);
    TableInfoHelper.initTableInfo(assistant, InvReplenishmentPolicy.class);
    TableInfoHelper.initTableInfo(assistant, Inventory.class);
    TableInfoHelper.initTableInfo(assistant, InvDemandForecast.class);
  }

  @BeforeEach
  void setUp() {
    service = new ReplenishmentComputeService(inventoryMapper, policyMapper, forecastMapper,
        suggestionMapper, itemMapper, demandAggregation, inTransitResolver);
    TenantContextHolder.setTenantId(TENANT);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  private static Inventory inventory(int available, Integer maxStock) {
    Inventory inventory = new Inventory();
    inventory.setId("inv-1");
    inventory.setSkuId("sku-1");
    inventory.setWarehouseId("wh-1");
    inventory.setAvailableStock(available);
    inventory.setSafetyStock(10);
    inventory.setMaxStock(maxStock);
    inventory.setAverageCost(new BigDecimal("10"));
    inventory.setDeleted(false);
    return inventory;
  }

  private static DemandAggregationQueryService.SkuDemand demand(String mu, boolean low) {
    return new DemandAggregationQueryService.SkuDemand("sku-1|wh-1",
        new BigDecimal(mu), 28, 28, low);
  }

  private static InvReplenishmentPolicy policy(String supplierId) {
    InvReplenishmentPolicy policy = new InvReplenishmentPolicy();
    policy.setSkuId("sku-1");
    policy.setWarehouseId("wh-1");
    policy.setLeadTimeDays(7);
    policy.setReviewPeriodDays(7);
    policy.setSafetyDays(new BigDecimal("3"));
    policy.setMinOrderQty(1);
    policy.setPackMultiple(1);
    policy.setSupplierId(supplierId);
    policy.setManualOverride(Boolean.FALSE);
    policy.setEnabled(Boolean.TRUE);
    return policy;
  }

  @Test
  @DisplayName("同周期重复触发返回已有建议单，不重复建单")
  void generateIsIdempotentPerBusinessKey() {
    InvReplenishmentSuggestion existing = new InvReplenishmentSuggestion();
    existing.setId("sug-existing");
    existing.setSuggestionNo("RP-OLD");
    existing.setStatus("DRAFT");
    existing.setItemCount(3);
    existing.setTotalSuggestedQty(30L);
    when(suggestionMapper.selectOne(any())).thenReturn(existing);

    ReplenishmentGenerateRequest request = new ReplenishmentGenerateRequest();
    request.setWarehouseId("wh-1");
    request.setPeriod("2026-W45");

    ReplenishmentComputeResult result = service.generate(request);

    assertThat(result.getExistingSuggestionId()).isEqualTo("sug-existing");
    assertThat(result.getTotalSuggestedQty()).isEqualTo(30L);
    verify(suggestionMapper, never()).insert(any(InvReplenishmentSuggestion.class));
    verify(itemMapper, never()).insert(any(InvReplenishmentSuggestionItem.class));
  }

  @Test
  @DisplayName("在途量已覆盖缺口时不建议补货（避免重复下单）")
  void inTransitPreventsDuplicateSuggestion() {
    when(suggestionMapper.selectOne(any())).thenReturn(null);
    when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(5, null)));
    when(policyMapper.selectList(any())).thenReturn(List.of(policy("sup-1")));
    when(demandAggregation.aggregateAll(anyInt()))
        .thenReturn(Map.of("sku-1|wh-1", demand("10", false)));
    // 在途 100 件：available(5) + inTransit(100) 远高于 ROP，不应触发
    when(inTransitResolver.resolve(anyString()))
        .thenReturn(InTransitQuantities.of(Map.of("sku-1|wh-1", 100)));

    ReplenishmentGenerateRequest request = new ReplenishmentGenerateRequest();
    request.setPeriod("2026-W45");
    ReplenishmentComputeResult result = service.generate(request);

    assertThat(result.getTriggeredItems()).isZero();
    assertThat(result.getTotalSuggestedQty()).isZero();
    Assertions.assertNoItemInserted(itemMapper);
  }

  @Test
  @DisplayName("库存低于 ROP 时触发补货，量按目标水位算")
  void shortageTriggersSuggestion() {
    when(suggestionMapper.selectOne(any())).thenReturn(null);
    when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(5, null)));
    when(policyMapper.selectList(any())).thenReturn(List.of(policy("sup-1")));
    when(demandAggregation.aggregateAll(anyInt()))
        .thenReturn(Map.of("sku-1|wh-1", demand("10", false)));
    when(inTransitResolver.resolve(anyString())).thenReturn(InTransitQuantities.empty());

    ReplenishmentGenerateRequest request = new ReplenishmentGenerateRequest();
    request.setPeriod("2026-W45");
    ReplenishmentComputeResult result = service.generate(request);

    // μ=10, L=7, review=7, safety=3 → SS=30, target=10*14+30=170, Q=165
    assertThat(result.getTriggeredItems()).isEqualTo(1);
    assertThat(result.getTotalSuggestedQty()).isEqualTo(165L);

    ArgumentCaptor<InvReplenishmentSuggestion> headCaptor =
        ArgumentCaptor.forClass(InvReplenishmentSuggestion.class);
    verify(suggestionMapper).insert(headCaptor.capture());
    assertThat(headCaptor.getValue().getStatus()).isEqualTo("DRAFT");
    assertThat(headCaptor.getValue().getBusinessKey()).contains("2026-W45");

    ArgumentCaptor<InvReplenishmentSuggestionItem> itemCaptor =
        ArgumentCaptor.forClass(InvReplenishmentSuggestionItem.class);
    verify(itemMapper).insert(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getSuggestedQty()).isEqualTo(165);
    assertThat(itemCaptor.getValue().getAvailableStock()).isEqualTo(5);
    assertThat(itemCaptor.getValue().getInTransitQty()).isZero();
  }

  @Test
  @DisplayName("dry-run 只算不落库")
  void dryRunDoesNotPersist() {
    when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(5, null)));
    when(policyMapper.selectList(any())).thenReturn(List.of(policy("sup-1")));
    when(demandAggregation.aggregateAll(anyInt()))
        .thenReturn(Map.of("sku-1|wh-1", demand("10", false)));
    when(inTransitResolver.resolve(anyString())).thenReturn(InTransitQuantities.empty());

    ReplenishmentGenerateRequest request = new ReplenishmentGenerateRequest();
    request.setDryRun(Boolean.TRUE);
    ReplenishmentComputeResult result = service.generate(request);

    assertThat(result.isDryRun()).isTrue();
    assertThat(result.getTriggeredItems()).isEqualTo(1);
    verify(suggestionMapper, never()).insert(any(InvReplenishmentSuggestion.class));
    verify(itemMapper, never()).insert(any(InvReplenishmentSuggestionItem.class));
  }

  @Test
  @DisplayName("没有销售出库历史的 SKU 不补货，避免无依据下单")
  void skuWithoutDemandIsSkipped() {
    when(suggestionMapper.selectOne(any())).thenReturn(null);
    when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(0, null)));
    when(policyMapper.selectList(any())).thenReturn(List.of(policy("sup-1")));
    when(demandAggregation.aggregateAll(anyInt())).thenReturn(Map.of());
    when(inTransitResolver.resolve(anyString())).thenReturn(InTransitQuantities.empty());

    ReplenishmentGenerateRequest request = new ReplenishmentGenerateRequest();
    request.setPeriod("2026-W45");
    ReplenishmentComputeResult result = service.generate(request);

    assertThat(result.getTriggeredItems()).isZero();
    assertThat(result.getSkippedItems()).isEqualTo(1);
  }

  @Test
  @DisplayName("冷启动/无策略的行走人审标记，不静默丢弃")
  void lowConfidenceIsFlaggedForManualReview() {
    when(suggestionMapper.selectOne(any())).thenReturn(null);
    when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(5, null)));
    // 没有策略：缺交期与供应商
    when(policyMapper.selectList(any())).thenReturn(List.of());
    when(demandAggregation.aggregateAll(anyInt()))
        .thenReturn(Map.of("sku-1|wh-1", demand("10", true)));
    when(inTransitResolver.resolve(anyString())).thenReturn(InTransitQuantities.empty());

    ReplenishmentGenerateRequest request = new ReplenishmentGenerateRequest();
    request.setPeriod("2026-W45");
    ReplenishmentComputeResult result = service.generate(request);

    assertThat(result.getNeedsManualItems()).isEqualTo(1);
    ArgumentCaptor<InvReplenishmentSuggestionItem> captor =
        ArgumentCaptor.forClass(InvReplenishmentSuggestionItem.class);
    verify(itemMapper).insert(captor.capture());
    assertThat(captor.getValue().getFlags())
        .contains("NEEDS_MANUAL")
        .contains("NO_LEAD_TIME")
        .contains("NO_SUPPLIER");
  }

  @Test
  @DisplayName("仓容上限会裁剪补货量并标记原因")
  void capacityCapIsReported() {
    when(suggestionMapper.selectOne(any())).thenReturn(null);
    // 现有 5 + 在途 0，maxStock=50 → 最多补 45
    when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(5, 50)));
    when(policyMapper.selectList(any())).thenReturn(List.of(policy("sup-1")));
    when(demandAggregation.aggregateAll(anyInt()))
        .thenReturn(Map.of("sku-1|wh-1", demand("10", false)));
    when(inTransitResolver.resolve(anyString())).thenReturn(InTransitQuantities.empty());

    ReplenishmentGenerateRequest request = new ReplenishmentGenerateRequest();
    request.setPeriod("2026-W45");
    service.generate(request);

    ArgumentCaptor<InvReplenishmentSuggestionItem> captor =
        ArgumentCaptor.forClass(InvReplenishmentSuggestionItem.class);
    verify(itemMapper).insert(captor.capture());
    assertThat(captor.getValue().getSuggestedQty()).isEqualTo(45);
    assertThat(captor.getValue().getFlags()).contains("CAPPED_BY_CAPACITY");
  }

  @Test
  @DisplayName("业务键按 tenant|warehouse|period 组成；全仓计算用 ALL")
  void businessKeyShape() {
    assertThat(ReplenishmentComputeService.businessKey(TENANT, "wh-1", "2026-W45"))
        .isEqualTo(TENANT + "|wh-1|2026-W45");
    assertThat(ReplenishmentComputeService.businessKey(TENANT, null, "2026-W45"))
        .isEqualTo(TENANT + "|ALL|2026-W45");
  }

  @Test
  @DisplayName("缺省周期按 ISO 周推导")
  void periodDefaultsToIsoWeek() {
    String period = ReplenishmentComputeService.resolvePeriod(null);
    assertThat(period).matches("\\d{4}-W\\d{2}");
    assertThat(ReplenishmentComputeService.resolvePeriod(" 2026-W45 ")).isEqualTo("2026-W45");
  }

  /** 小工具：读起来比 never() 直白，失败信息也更清楚。 */
  private static final class Assertions {
    static void assertNoItemInserted(InvReplenishmentSuggestionItemMapper mapper) {
      verify(mapper, never()).insert(any(InvReplenishmentSuggestionItem.class));
    }
  }
}
