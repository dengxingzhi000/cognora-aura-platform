package com.cognora.platform.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补货建议明细（inv_replenishment_suggestion_item）。
 *
 * <p>每一行冻结「算量那一刻」的输入与输出：可用量、在途量、ROP、SS、目标水位与建议量。
 * 之后台账怎么变都不回写本行，人审看到的就是系统当时据以决策的数字。
 *
 * <p>{@code flags} 是逗号分隔的人审提示位（{@link ReplenishmentFlags}），
 * {@code reasonSnapshot} 是命中规则与裁剪原因的 JSON 快照——两者共同回答
 * 「为什么建议补这么多」。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
@TableName("inv_replenishment_suggestion_item")
public class InvReplenishmentSuggestionItem {

  @TableField(value = "tenant_id", fill = FieldFill.INSERT)
  private String tenantId;

  @TableId(type = IdType.ASSIGN_UUID)
  private String id;

  private String suggestionId;

  private String skuId;

  private String warehouseId;

  /** 计算时刻的可用量快照 */
  private Integer availableStock;

  /** 计算时刻的在途量快照（采购单未收货量 + 本周期已采纳未收货的量） */
  private Integer inTransitQty;

  private BigDecimal reorderPoint;

  private BigDecimal safetyStock;

  private BigDecimal targetStock;

  /** 模型建议量（已过 MOQ/包装倍数/仓容/预算裁剪） */
  private Integer suggestedQty;

  /** 人工采纳量；null 表示未改量 */
  private Integer adoptedQty;

  private String supplierId;

  /** 逗号分隔的人审提示位，见 {@link ReplenishmentFlags} */
  private String flags;

  /** 决策快照 JSON：公式入参、命中约束、裁剪原因 */
  private String reasonSnapshot;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updateTime;

  @TableLogic
  private Boolean deleted;

  /** 该行最终要转出的数量：采纳量优先，否则用建议量。 */
  public int effectiveQty() {
    Integer adopted = adoptedQty;
    if (adopted != null && adopted >= 0) {
      return adopted;
    }
    return suggestedQty == null ? 0 : suggestedQty;
  }

  /** 该行是否被人工改过量。 */
  public boolean isQuantityAdjusted() {
    return adoptedQty != null && !adoptedQty.equals(suggestedQty);
  }
}
