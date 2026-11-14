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
 * SKU×仓补货策略（inv_replenishment_policy）。
 *
 * <p>补货计算的入参全部来自这里，不再散落在代码常量里：交期 {@code leadTimeDays}、
 * 复核周期 {@code reviewPeriodDays}、安全库存天数 {@code safetyDays}、MOQ 与包装倍数。
 *
 * <p>{@code manualOverride=true} 表示业务明确要用台账上的
 * {@code inv_inventory.safety_stock / max_stock} 作为上下限，此时算出的
 * {@code SS}/{@code targetStock} 只作为参考值展示，不参与裁剪——人工优先。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
@TableName("inv_replenishment_policy")
public class InvReplenishmentPolicy {

  @TableField(value = "tenant_id", fill = FieldFill.INSERT)
  private String tenantId;

  @TableId(type = IdType.ASSIGN_UUID)
  private String id;

  private String skuId;

  private String warehouseId;

  /** A / B / C，决定默认服务水平（A=98% B=95% C=90%） */
  private String abcClass;

  /** 目标服务水平 0..1 */
  private BigDecimal serviceLevel;

  /** 供应交期 L（天） */
  private Integer leadTimeDays;

  /** 补货复核周期（天） */
  private Integer reviewPeriodDays;

  /** 规则版安全库存天数，SS ≈ μ × safetyDays */
  private BigDecimal safetyDays;

  /** 供应商最小起订量 */
  private Integer minOrderQty;

  /** 包装倍数：补货量向上取到它的整数倍 */
  private Integer packMultiple;

  /** MVP 的单一供应商；统计版由 decision-engine 打分选出 */
  private String supplierId;

  /** true 时以台账 safety_stock/max_stock 为准 */
  private Boolean manualOverride;

  private Boolean enabled;

  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updateTime;

  @TableLogic
  private Boolean deleted;
}
