package com.cognora.platform.inventory.event;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 补货建议单转采购计划事件（经事务性 outbox 投递）。
 *
 * <p>作为 outbox 行的 {@code payload} 写入，与建议单状态变更同一事务；
 * {@code OutboxPoller} 把它转发到 Kafka 主题 {@code cognora.replenishmentsuggestion}
 * （聚合类型小写，见 {@code OutboxPoller#buildTopic}），由
 * {@code scm-purchase} 的消费者建 PurPlan(DRAFT)。
 *
 * <p><b>字段即契约：</b>消费端 {@code PurPlanFromReplenishmentDTO} 按同名字段反序列化，
 * 两端不共享编译期类型。改字段名必须两端同批发布。
 *
 * <p><b>幂等：</b>{@code suggestionNo} 就是消费端的去重键（plan_no 默认等于它），
 * 重复投递只会命中同一张计划。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Getter
public class ReplenishmentConvertedEvent {

  /** outbox event_type */
  public static final String EVENT_TYPE = "REPLENISHMENT_CONVERTED";

  /** outbox aggregate_type，决定 Kafka 主题后缀 */
  public static final String AGGREGATE_TYPE = "ReplenishmentSuggestion";

  private final UUID tenantId;
  private final String suggestionId;
  private final String suggestionNo;
  private final String period;
  private final String warehouseId;

  /** 消费端的计划号；这里固定由建议单号推导，保证幂等 */
  private final String planNo;
  private final String planName;
  private final String planPeriod;

  /** 期望到货日期：今天 + 交期，缺省 30 天 */
  private final String expectedArrivalDate;

  /** 明细；materialCode 已由本侧完成 SKU→material 映射 */
  private final List<Item> items;

  public ReplenishmentConvertedEvent(UUID tenantId, String suggestionId, String suggestionNo,
                                     String period, String warehouseId, String planNo,
                                     String planName, String planPeriod, String expectedArrivalDate,
                                     List<Item> items) {
    this.tenantId = tenantId;
    this.suggestionId = suggestionId;
    this.suggestionNo = suggestionNo;
    this.period = period;
    this.warehouseId = warehouseId;
    this.planNo = planNo;
    this.planName = planName;
    this.planPeriod = planPeriod;
    this.expectedArrivalDate = expectedArrivalDate;
    this.items = items;
  }

  /** 一条采购计划明细。 */
  @Getter
  public static class Item {
    private final String skuId;
    private final String materialCode;
    private final String materialName;
    private final String materialCategory;
    private final BigDecimal plannedQuantity;
    private final String unit;
    private final BigDecimal budgetPrice;
    private final String supplierId;
    private final String remark;

    public Item(String skuId, String materialCode, String materialName, String materialCategory,
                BigDecimal plannedQuantity, String unit, BigDecimal budgetPrice, String supplierId,
                String remark) {
      this.skuId = skuId;
      this.materialCode = materialCode;
      this.materialName = materialName;
      this.materialCategory = materialCategory;
      this.plannedQuantity = plannedQuantity;
      this.unit = unit;
      this.budgetPrice = budgetPrice;
      this.supplierId = supplierId;
      this.remark = remark;
    }
  }
}
