package com.cognora.platform.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 补货策略维护请求（upsert 语义：同 SKU×仓 已存在则更新）。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentPolicyRequest {

  @NotBlank(message = "SKU 不能为空")
  private String skuId;

  @NotBlank(message = "仓库不能为空")
  private String warehouseId;

  /** A / B / C；为空默认 C */
  private String abcClass;

  /** 服务水平 0..1；为空按 ABC 默认（A=0.98 B=0.95 C=0.90） */
  private BigDecimal serviceLevel;

  /** 供应交期（天） */
  @Min(value = 0, message = "交期不能为负数")
  @Max(value = 365, message = "交期最大为 365 天")
  private Integer leadTimeDays;

  /** 复核周期（天） */
  @Min(value = 0, message = "复核周期不能为负数")
  @Max(value = 365, message = "复核周期最大为 365 天")
  private Integer reviewPeriodDays;

  /** 规则版安全库存天数 */
  @Min(value = 0, message = "安全库存天数不能为负数")
  private BigDecimal safetyDays;

  @Min(value = 1, message = "MOQ 最小为 1")
  private Integer minOrderQty;

  @Min(value = 1, message = "包装倍数最小为 1")
  private Integer packMultiple;

  private String supplierId;

  private Boolean manualOverride;

  private Boolean enabled;

  private String remark;

  private String operatorId;
}
