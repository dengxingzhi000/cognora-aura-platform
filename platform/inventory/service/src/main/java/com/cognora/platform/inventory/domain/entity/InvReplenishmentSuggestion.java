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
 * 补货建议单头（inv_replenishment_suggestion）。
 *
 * <p>{@code businessKey} 是幂等键 {@code tenant|warehouse|period}：同一个补货周期
 * 重复触发计算时，唯一约束挡下第二张单，调用方返回已有单据而不是再建一张。
 *
 * <p>{@code convertError} 记录转采购计划失败的原因（例如 SKU→material 映射缺失）。
 * 失败时单据停在 ADOPTED 而不是回滚：人审决策已经生效，重试转换不应让人重新点一遍。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
@TableName("inv_replenishment_suggestion")
public class InvReplenishmentSuggestion {

  @TableField(value = "tenant_id", fill = FieldFill.INSERT)
  private String tenantId;

  @TableId(type = IdType.ASSIGN_UUID)
  private String id;

  private String suggestionNo;

  private String warehouseId;

  /** 补货周期，如 2026-W45 */
  private String period;

  /** DRAFT / ADOPTED / CONVERTED / REJECTED / CANCELLED */
  private String status;

  /** 幂等键：tenant + warehouse + period */
  private String businessKey;

  private Integer itemCount;

  private Long totalSuggestedQty;

  private Long totalAdoptedQty;

  /** 转出的采购计划；成功回写 */
  private String purPlanId;

  private String purPlanNo;

  /** 转换失败原因，成功时为 null */
  private String convertError;

  private String rejectReason;

  private String remark;

  private String createdBy;

  private String adoptedBy;

  private LocalDateTime adoptedAt;

  private LocalDateTime convertedAt;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updateTime;

  @TableLogic
  private Boolean deleted;

  /** 采纳后待转出的总量：人工改过量则以采纳量为准。 */
  public long effectiveTotalQty() {
    if (totalAdoptedQty != null) {
      return totalAdoptedQty;
    }
    return totalSuggestedQty == null ? 0L : totalSuggestedQty;
  }

  /** 供只读展示：把采纳量/建议量整理成两个非空数字。 */
  public static long nvl(Long value) {
    return value == null ? 0L : value;
  }

  /** BigDecimal 版本的兜底，避免各调用方各写一份。 */
  public static BigDecimal nvl(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
