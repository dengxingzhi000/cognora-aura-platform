package com.cognora.scm.purchase.listener;

import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.scm.purchase.domain.entity.PurPlan;
import com.cognora.scm.purchase.domain.entity.PurPlanItem;
import com.cognora.scm.purchase.service.command.PurPlanCommandService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 消费 {@code REPLENISHMENT_CONVERTED}，把补货建议单建成采购计划草稿。
 *
 * <p><b>消息形态：</b>{@code OutboxPoller} 把 outbox 行整体塞进
 * {@code MessageEnvelope.data}（字符串），外层是 envelope、内层是 outbox 行，
 * outbox 行的 {@code payload} 又是字符串化 JSON。因此这里要剥两层再读字段。
 *
 * <p><b>为什么不共享 DTO 类型：</b>库存侧的事件类在 {@code platform-inventory}，
 * 采购侧不该依赖它的编译期类型（会形成反向依赖并导致两边同时改字段才能上线）。
 * 字段名即契约，这里按名字读取，缺字段按默认值处理。
 *
 * <p><b>幂等：</b>去重键是建议单号——{@code plan_no} 由 {@code RP-<建议单号>} 推导，
 * {@link PurPlanCommandService#createFromReplenishment} 命中已存在的同号计划时原样返回。
 * 重复投递因此不会建第二张计划，也不会抛错卡住分区。
 *
 * <p><b>租户上下文：</b>Kafka 监听线程没有租户，必须用事件里的 tenantId 包一层，
 * 否则 {@code AuditMetaObjectHandler} 填不出 {@code tenant_id}，写入会失败或被
 * 租户过滤器漏掉。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
@Component
public class ReplenishmentConvertedConsumer {

  private static final String EXPECTED_EVENT_TYPE = "REPLENISHMENT_CONVERTED";

  private final ObjectMapper objectMapper;
  private final PurPlanCommandService purPlanCommandService;

  public ReplenishmentConvertedConsumer(ObjectMapper objectMapper,
                                        PurPlanCommandService purPlanCommandService) {
    // 事件会随 inventory 侧演进增加字段，不要因此拒收
    this.objectMapper = objectMapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.purPlanCommandService = purPlanCommandService;
  }

  @KafkaListener(
      topics = "${purchase.replenishment-events-topic:cognora.replenishmentsuggestion}",
      groupId = "${spring.kafka.consumer.group-id:purchase-replenishment-events}")
  public void onReplenishmentConverted(String message) {
    JsonNode envelope = parse(message);
    if (envelope == null) {
      return;
    }

    JsonNode outbox = readNested(envelope.get("data"));
    if (outbox == null) {
      log.warn("补货转采购事件缺少 outbox 载荷，跳过");
      return;
    }
    String eventType = text(outbox, "eventType");
    if (eventType != null && !EXPECTED_EVENT_TYPE.equals(eventType)) {
      return;
    }

    JsonNode payload = readNested(outbox.get("payload"));
    if (payload == null) {
      log.warn("补货转采购事件缺少 payload，outboxId={}", text(outbox, "id"));
      return;
    }

    String suggestionNo = text(payload, "suggestionNo");
    if (!StringUtils.hasText(suggestionNo)) {
      log.warn("补货转采购事件缺少建议单号，outboxId={}", text(outbox, "id"));
      return;
    }

    UUID tenantId = parseTenant(preferText(payload, outbox, "tenantId"));
    if (tenantId == null) {
      log.warn("补货转采购事件缺少可用租户，suggestionNo={}", suggestionNo);
      return;
    }

    List<PurPlanItem> items = toPlanItems(payload);
    if (items.isEmpty()) {
      log.warn("补货转采购事件没有明细，suggestionNo={}", suggestionNo);
      return;
    }

    String planNo = StringUtils.hasText(text(payload, "planNo"))
        ? text(payload, "planNo").trim()
        : "RP-" + suggestionNo;
    String planName = text(payload, "planName");
    String planPeriod = text(payload, "planPeriod");
    String remark = "由补货建议 " + suggestionNo + " 转入";

    try {
      PurPlan plan = TenantContextHolder.executeInTenantContext(tenantId,
          () -> purPlanCommandService.createFromReplenishment(
              planNo, planName, planPeriod, remark, items));
      log.info("补货建议 {} 已生成采购计划 {}: items={}",
          suggestionNo, plan.getPlanNo(), items.size());
    } catch (RuntimeException e) {
      // 建议单在 inventory 侧已是 CONVERTED；这里抛出让 Kafka 重试/进 DLT，
      // 人工也可以在采购侧按同一 plan_no 补建（幂等键保证不会重复）。
      log.warn("补货建议 {} 转采购计划失败: {}", suggestionNo, e.getMessage());
      throw e;
    }
  }

  // ---------------------------------------------------------------- mapping

  private List<PurPlanItem> toPlanItems(JsonNode payload) {
    List<PurPlanItem> items = new ArrayList<>();
    JsonNode array = payload.get("items");
    if (array == null || !array.isArray()) {
      return items;
    }
    for (JsonNode node : array) {
      String materialCode = text(node, "materialCode");
      if (!StringUtils.hasText(materialCode)) {
        continue;
      }
      BigDecimal quantity = decimal(node, "plannedQuantity");
      if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      PurPlanItem item = new PurPlanItem()
          .setMaterialCode(materialCode)
          .setMaterialName(text(node, "materialName"))
          .setMaterialCategory(text(node, "materialCategory"))
          .setPlannedQuantity(quantity)
          .setPurchasedQuantity(BigDecimal.ZERO)
          .setUnit(StringUtils.hasText(text(node, "unit")) ? text(node, "unit") : "件")
          .setBudgetPrice(decimal(node, "budgetPrice"))
          .setRemark(text(node, "remark"));
      items.add(item);
    }
    return items;
  }

  // ---------------------------------------------------------------- json helpers

  private JsonNode parse(String message) {
    if (!StringUtils.hasText(message)) {
      return null;
    }
    try {
      return objectMapper.readTree(message);
    } catch (Exception e) {
      log.warn("补货转采购事件不是合法 JSON，跳过: {}", e.getMessage());
      return null;
    }
  }

  /** envelope.data / outbox.payload 可能是 JSON 字符串，也可能是已展开的对象。 */
  private JsonNode readNested(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      return node;
    }
    try {
      return objectMapper.readTree(node.asText());
    } catch (Exception e) {
      log.debug("嵌套载荷无法解析为 JSON: {}", e.getMessage());
      return null;
    }
  }

  private String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  /** payload 优先，缺失时回退到 outbox 行（Poller 会把 tenantId 放在 outbox 上）。 */
  private String preferText(JsonNode payload, JsonNode outbox, String field) {
    String fromPayload = text(payload, field);
    return StringUtils.hasText(fromPayload) ? fromPayload : text(outbox, field);
  }

  private BigDecimal decimal(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    try {
      return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
    } catch (NumberFormatException e) {
      log.debug("字段 {} 不是合法数字: {}", field, value.asText());
      return null;
    }
  }

  private UUID parseTenant(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      log.warn("租户 ID 不是合法 UUID: {}", value);
      return null;
    }
  }
}
