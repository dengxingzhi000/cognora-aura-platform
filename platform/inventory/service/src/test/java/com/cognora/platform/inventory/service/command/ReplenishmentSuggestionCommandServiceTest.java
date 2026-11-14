package com.cognora.platform.inventory.service.command;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cognora.platform.common.integration.outbox.OutboxService;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.inventory.domain.ReplenishmentFlags;
import com.cognora.platform.inventory.domain.ReplenishmentStatus;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import com.cognora.platform.inventory.dto.ReplenishmentDecisionRequest;
import com.cognora.platform.inventory.dto.ReplenishmentItemAdjustRequest;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionResponse;
import com.cognora.platform.inventory.event.ReplenishmentConvertedEvent;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionItemMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补货建议单命令服务用例：状态机硬规则、驳回必带原因、转采购计划的幂等与映射。
 */
class ReplenishmentSuggestionCommandServiceTest {

  private final InvReplenishmentSuggestionMapper suggestionMapper =
      mock(InvReplenishmentSuggestionMapper.class);
  private final InvReplenishmentSuggestionItemMapper itemMapper =
      mock(InvReplenishmentSuggestionItemMapper.class);
  private final OutboxService outboxService = mock(OutboxService.class);
  private SkuMaterialMappingResolver mappingResolver;

  private ReplenishmentSuggestionCommandService service;

  @BeforeAll
  static void initLambdaCache() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, InvReplenishmentSuggestion.class);
    TableInfoHelper.initTableInfo(assistant, InvReplenishmentSuggestionItem.class);
  }

  @BeforeEach
  void setUp() {
    mappingResolver = mock(SkuMaterialMappingResolver.class);
    service = new ReplenishmentSuggestionCommandService(
        suggestionMapper, itemMapper, outboxService, mappingResolver);
    TenantContextHolder.setTenantId(UUID.randomUUID());
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  private static InvReplenishmentSuggestion head(ReplenishmentStatus status) {
    InvReplenishmentSuggestion head = new InvReplenishmentSuggestion();
    head.setId("sug-1");
    head.setSuggestionNo("RP20261110-ABC123");
    head.setPeriod("2026-W45");
    head.setStatus(status.name());
    head.setDeleted(false);
    head.setItemCount(1);
    head.setTotalSuggestedQty(10L);
    return head;
  }

  private static InvReplenishmentSuggestionItem item(String id, int suggested, Integer adopted) {
    InvReplenishmentSuggestionItem item = new InvReplenishmentSuggestionItem();
    item.setId(id);
    item.setSuggestionId("sug-1");
    item.setSkuId("sku-1");
    item.setWarehouseId("wh-1");
    item.setSuggestedQty(suggested);
    item.setAdoptedQty(adopted);
    return item;
  }

  // ------------------------------------------------------------ adjustItem

  @Test
  @DisplayName("草稿可改量，改量后以采纳量作为转出数量")
  void adjustIsAllowedOnlyOnDraft() {
    when(suggestionMapper.selectById("sug-1")).thenReturn(head(ReplenishmentStatus.DRAFT));
    InvReplenishmentSuggestionItem line = item("item-1", 10, null);
    when(itemMapper.selectById("item-1")).thenReturn(line);
    when(itemMapper.selectList(any())).thenReturn(List.of(line));

    ReplenishmentItemAdjustRequest request = new ReplenishmentItemAdjustRequest();
    request.setAdoptedQty(7);
    request.setReason("促销结束，减量");

    service.adjustItem("sug-1", "item-1", request);

    assertThat(line.getAdoptedQty()).isEqualTo(7);
    assertThat(line.effectiveQty()).isEqualTo(7);
    assertThat(line.isQuantityAdjusted()).isTrue();
    assertThat(line.getReasonSnapshot()).contains("促销结束");
    verify(itemMapper).updateById(line);
  }

  @Test
  @DisplayName("已采纳的建议单不能改量，否则采纳量与转出量会不一致")
  void adjustIsRejectedAfterAdoption() {
    when(suggestionMapper.selectById("sug-1")).thenReturn(head(ReplenishmentStatus.ADOPTED));

    ReplenishmentItemAdjustRequest request = new ReplenishmentItemAdjustRequest();
    request.setAdoptedQty(5);

    assertThatThrownBy(() -> service.adjustItem("sug-1", "item-1", request))
        .hasMessageContaining("只有草稿状态的建议单可以改量");
    verify(itemMapper, never()).updateById(any(InvReplenishmentSuggestionItem.class));
  }

  // ------------------------------------------------------------ adopt / reject

  @Test
  @DisplayName("采纳时未改量的明细按建议量补齐，状态推进到 ADOPTED")
  void adoptFillsAdoptedQuantityFromSuggested() {
    InvReplenishmentSuggestion suggestion = head(ReplenishmentStatus.DRAFT);
    when(suggestionMapper.selectById("sug-1")).thenReturn(suggestion);
    InvReplenishmentSuggestionItem line = item("item-1", 10, null);
    when(itemMapper.selectList(any())).thenReturn(List.of(line));

    ReplenishmentDecisionRequest request = new ReplenishmentDecisionRequest();
    request.setOperatorId("user-1");

    service.adopt("sug-1", request);

    assertThat(line.getAdoptedQty()).isEqualTo(10);
    assertThat(suggestion.getStatus()).isEqualTo(ReplenishmentStatus.ADOPTED.name());
    assertThat(suggestion.getAdoptedBy()).isEqualTo("user-1");
  }

  @Test
  @DisplayName("驳回必须带原因，否则无法做反馈闭环")
  void rejectRequiresAReason() {
    when(suggestionMapper.selectById("sug-1")).thenReturn(head(ReplenishmentStatus.DRAFT));

    ReplenishmentDecisionRequest request = new ReplenishmentDecisionRequest();
    request.setReason("   ");

    assertThatThrownBy(() -> service.reject("sug-1", request))
        .hasMessageContaining("驳回必须填写原因");
    verify(suggestionMapper, never()).updateById(any(InvReplenishmentSuggestion.class));
  }

  @Test
  @DisplayName("驳回成功时落原因并进入终态")
  void rejectRecordsTheReason() {
    InvReplenishmentSuggestion suggestion = head(ReplenishmentStatus.DRAFT);
    when(suggestionMapper.selectById("sug-1")).thenReturn(suggestion);
    when(itemMapper.selectList(any())).thenReturn(List.of());

    ReplenishmentDecisionRequest request = new ReplenishmentDecisionRequest();
    request.setReason("本周已手工下单");

    service.reject("sug-1", request);

    assertThat(suggestion.getStatus()).isEqualTo(ReplenishmentStatus.REJECTED.name());
    assertThat(suggestion.getRejectReason()).isEqualTo("本周已手工下单");
  }

  // ------------------------------------------------------------ convert

  @Test
  @DisplayName("只有 ADOPTED 可以转采购计划")
  void convertRequiresAdoptedStatus() {
    when(suggestionMapper.selectById("sug-1")).thenReturn(head(ReplenishmentStatus.DRAFT));

    assertThatThrownBy(() -> service.convert("sug-1", null))
        .hasMessageContaining("只有已采纳的建议单可以转采购计划");
    verify(outboxService, never()).save(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("转换成功时状态与 outbox 事件同事务写入，并用建议单号做幂等键")
  void convertWritesOutboxEventWithSuggestionNoKey() {
    InvReplenishmentSuggestion suggestion = head(ReplenishmentStatus.ADOPTED);
    when(suggestionMapper.selectById("sug-1")).thenReturn(suggestion);
    when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 10, 12)));
    when(mappingResolver.resolve("sku-1"))
        .thenReturn(new SkuMaterialMapping("M-001", "螺丝", "五金", "件"));

    service.convert("sug-1", null);

    assertThat(suggestion.getStatus()).isEqualTo(ReplenishmentStatus.CONVERTED.name());
    assertThat(suggestion.getPurPlanNo()).isEqualTo("RP-RP20261110-ABC123");
    verify(outboxService).save(
        eq(ReplenishmentConvertedEvent.EVENT_TYPE),
        eq(ReplenishmentConvertedEvent.AGGREGATE_TYPE),
        eq("RP20261110-ABC123"),
        any(ReplenishmentConvertedEvent.class),
        any());
  }

  @Test
  @DisplayName("SKU→material 映射缺失时转换失败并回写原因，不做部分转出")
  void convertFailsOnMissingMaterialMapping() {
    InvReplenishmentSuggestion suggestion = head(ReplenishmentStatus.ADOPTED);
    when(suggestionMapper.selectById("sug-1")).thenReturn(suggestion);
    when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 10, 10)));
    when(mappingResolver.resolve("sku-1")).thenReturn(null);

    assertThatThrownBy(() -> service.convert("sug-1", null))
        .hasMessageContaining("SKU→material 映射缺失");

    assertThat(suggestion.getConvertError()).contains("映射缺失");
    verify(outboxService, never()).save(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("采纳量全为 0 时拒绝转换，避免生成空计划")
  void convertRejectsZeroQuantities() {
    when(suggestionMapper.selectById("sug-1")).thenReturn(head(ReplenishmentStatus.ADOPTED));
    when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 10, 0)));

    assertThatThrownBy(() -> service.convert("sug-1", null))
        .hasMessageContaining("没有可转出的数量");
    verify(outboxService, never()).save(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("已转换的单据不可再次转换（终态）")
  void convertIsRejectedOnTerminalStatus() {
    when(suggestionMapper.selectById("sug-1")).thenReturn(head(ReplenishmentStatus.CONVERTED));

    assertThatThrownBy(() -> service.convert("sug-1", null))
        .hasMessageContaining("只有已采纳的建议单可以转采购计划");
  }

  @Test
  @DisplayName("详情返回明细并回显有效量与改量标记")
  void detailExposesEffectiveQuantity() {
    when(suggestionMapper.selectById("sug-1")).thenReturn(head(ReplenishmentStatus.ADOPTED));
    when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 10, 6)));

    ReplenishmentSuggestionResponse response = service.detail("sug-1");

    assertThat(response.getItems()).hasSize(1);
    assertThat(response.getItems().get(0).getEffectiveQty()).isEqualTo(6);
    assertThat(response.getItems().get(0).getAdjusted()).isTrue();
  }

  @Test
  @DisplayName("明细不存在或不属于本单时报 404 语义")
  void adjustRejectsForeignItem() {
    when(suggestionMapper.selectById("sug-1")).thenReturn(head(ReplenishmentStatus.DRAFT));
    InvReplenishmentSuggestionItem foreign = item("item-x", 5, null);
    foreign.setSuggestionId("other");
    when(itemMapper.selectById("item-x")).thenReturn(foreign);

    assertThatThrownBy(() -> service.adjustItem("sug-1", "item-x",
        new ReplenishmentItemAdjustRequest()))
        .hasMessageContaining("不属于本单");
  }

  @Test
  @DisplayName("flags 序列化往返：空集合存 null，未知标记被忽略")
  void flagsRoundTrip() {
    assertThat(ReplenishmentFlags.join(java.util.Set.of())).isNull();
    String joined = ReplenishmentFlags.join(new java.util.LinkedHashSet<>(
        List.of(ReplenishmentFlags.NEEDS_MANUAL, ReplenishmentFlags.NO_SUPPLIER)));
    assertThat(joined).isEqualTo("NEEDS_MANUAL,NO_SUPPLIER");
    assertThat(ReplenishmentFlags.parse(joined))
        .containsExactly(ReplenishmentFlags.NEEDS_MANUAL, ReplenishmentFlags.NO_SUPPLIER);
    assertThat(ReplenishmentFlags.parse("NEEDS_MANUAL,UNKNOWN_FLAG"))
        .containsExactly(ReplenishmentFlags.NEEDS_MANUAL);
  }
}
