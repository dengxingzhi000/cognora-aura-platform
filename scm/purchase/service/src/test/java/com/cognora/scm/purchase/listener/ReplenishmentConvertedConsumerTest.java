package com.cognora.scm.purchase.listener;

import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.scm.purchase.domain.PurPlanStatus;
import com.cognora.scm.purchase.domain.entity.PurPlan;
import com.cognora.scm.purchase.domain.entity.PurPlanItem;
import com.cognora.scm.purchase.service.command.PurPlanCommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费侧用例：剥两层信封、租户上下文、幂等键与明细映射。
 */
class ReplenishmentConvertedConsumerTest {

  private static final String TENANT = "019a0aee-0000-7000-8000-000000000002";

  private final PurPlanCommandService planCommandService = mock(PurPlanCommandService.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private ReplenishmentConvertedConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new ReplenishmentConvertedConsumer(objectMapper, planCommandService);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 构造与 OutboxPoller 实际投递一致的报文：envelope.data 是字符串化的 outbox 行。 */
  private String message(String suggestionNo, String itemsJson) throws Exception {
    String payload = "{\"tenantId\":\"" + TENANT + "\","
        + "\"suggestionId\":\"sug-1\","
        + "\"suggestionNo\":\"" + suggestionNo + "\","
        + "\"period\":\"2026-W45\","
        + "\"warehouseId\":\"wh-1\","
        + "\"planNo\":\"RP-" + suggestionNo + "\","
        + "\"planName\":\"补货计划\","
        + "\"planPeriod\":\"2026-W45\","
        + "\"items\":" + itemsJson + "}";

    String outbox = objectMapper.writeValueAsString(java.util.Map.of(
        "id", "outbox-1",
        "eventType", "REPLENISHMENT_CONVERTED",
        "aggregateType", "ReplenishmentSuggestion",
        "aggregateId", suggestionNo,
        "tenantId", TENANT,
        "payload", payload));

    return objectMapper.writeValueAsString(java.util.Map.of(
        "type", "REPLENISHMENT_CONVERTED",
        "source", "outbox",
        "tenantId", TENANT,
        "data", outbox));
  }

  private static final String ONE_ITEM = "[{\"skuId\":\"sku-1\",\"materialCode\":\"M-001\","
      + "\"materialName\":\"螺丝\",\"materialCategory\":\"五金\",\"plannedQuantity\":12,"
      + "\"unit\":\"件\"}]";

  @Test
  @DisplayName("按 suggestionNo 推导 plan_no 并建 DRAFT 计划，租户上下文来自事件")
  void consumesEventAndCreatesDraftPlan() throws Exception {
    UUID tenant = UUID.fromString(TENANT);
    when(planCommandService.createFromReplenishment(anyString(), any(), any(), anyString(), anyList()))
        .thenAnswer(invocation -> {
          // 断言监听线程里确实被包进了事件租户的上下文
          assertThat(TenantContextHolder.getRequiredTenantId()).isEqualTo(tenant);
          return new PurPlan().setPlanNo("RP-RP20261110-ABC123")
              .setStatus(PurPlanStatus.DRAFT.getCode());
        });

    consumer.onReplenishmentConverted(message("RP20261110-ABC123", ONE_ITEM));

    ArgumentCaptor<List<PurPlanItem>> captor = ArgumentCaptor.forClass(List.class);
    verify(planCommandService).createFromReplenishment(
        eq("RP-RP20261110-ABC123"), eq("补货计划"), eq("2026-W45"), anyString(), captor.capture());

    List<PurPlanItem> items = captor.getValue();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getMaterialCode()).isEqualTo("M-001");
    assertThat(items.get(0).getPlannedQuantity()).isEqualByComparingTo("12");
    assertThat(items.get(0).getUnit()).isEqualTo("件");
  }

  @Test
  @DisplayName("非法 JSON 只跳过，不抛错卡住分区")
  void malformedMessageIsSkipped() {
    consumer.onReplenishmentConverted("not json at all");
    verify(planCommandService, never())
        .createFromReplenishment(anyString(), any(), any(), anyString(), anyList());
  }

  @Test
  @DisplayName("其它事件类型直接忽略")
  void otherEventTypesAreIgnored() throws Exception {
    consumer.onReplenishmentConverted(objectMapper.writeValueAsString(java.util.Map.of(
        "data", objectMapper.writeValueAsString(java.util.Map.of(
            "eventType", "ORDER_CREATED",
            "payload", "{}")))));

    verify(planCommandService, never())
        .createFromReplenishment(anyString(), any(), any(), anyString(), anyList());
  }

  @Test
  @DisplayName("缺少建议单号时不建计划")
  void missingSuggestionNoIsRejected() throws Exception {
    consumer.onReplenishmentConverted(message("", ONE_ITEM));

    verify(planCommandService, never())
        .createFromReplenishment(anyString(), any(), any(), anyString(), anyList());
  }

  @Test
  @DisplayName("明细缺少 materialCode 的行被丢弃，全空则不建计划")
  void itemsWithoutMaterialCodeAreDropped() throws Exception {
    String badItems = "[{\"skuId\":\"sku-1\",\"plannedQuantity\":5}]";
    consumer.onReplenishmentConverted(message("RP-1", badItems));

    verify(planCommandService, never())
        .createFromReplenishment(anyString(), any(), any(), anyString(), anyList());
  }

  @Test
  @DisplayName("数量非法的行被丢弃")
  void itemsWithInvalidQuantityAreDropped() throws Exception {
    String zeroQty = "[{\"skuId\":\"sku-1\",\"materialCode\":\"M-1\",\"plannedQuantity\":0}]";
    consumer.onReplenishmentConverted(message("RP-2", zeroQty));

    verify(planCommandService, never())
        .createFromReplenishment(anyString(), any(), any(), anyString(), anyList());
  }

  @Test
  @DisplayName("payload 缺少 tenantId 时回退到 outbox 行的 tenantId")
  void tenantFallbackToOutboxRow() throws Exception {
    String payload = "{\"suggestionNo\":\"RP-3\",\"items\":" + ONE_ITEM + "}";
    String outbox = objectMapper.writeValueAsString(java.util.Map.of(
        "id", "outbox-3",
        "eventType", "REPLENISHMENT_CONVERTED",
        "tenantId", TENANT,
        "payload", payload));
    String envelope = objectMapper.writeValueAsString(java.util.Map.of(
        "data", outbox));

    when(planCommandService.createFromReplenishment(anyString(), any(), any(), anyString(), anyList()))
        .thenReturn(new PurPlan().setPlanNo("RP-RP-3"));

    consumer.onReplenishmentConverted(envelope);

    verify(planCommandService).createFromReplenishment(
        eq("RP-RP-3"), isNull(), isNull(), anyString(), anyList());
  }
}
