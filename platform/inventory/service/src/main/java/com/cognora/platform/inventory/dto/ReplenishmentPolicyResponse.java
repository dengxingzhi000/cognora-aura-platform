package com.cognora.platform.inventory.dto;

import com.cognora.platform.inventory.domain.entity.InvReplenishmentPolicy;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补货策略响应。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentPolicyResponse {

  private String id;
  private String skuId;
  private String warehouseId;
  private String abcClass;
  private BigDecimal serviceLevel;
  private Integer leadTimeDays;
  private Integer reviewPeriodDays;
  private BigDecimal safetyDays;
  private Integer minOrderQty;
  private Integer packMultiple;
  private String supplierId;
  private Boolean manualOverride;
  private Boolean enabled;
  private String remark;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;

  public static ReplenishmentPolicyResponse from(InvReplenishmentPolicy policy) {
    ReplenishmentPolicyResponse response = new ReplenishmentPolicyResponse();
    response.setId(policy.getId());
    response.setSkuId(policy.getSkuId());
    response.setWarehouseId(policy.getWarehouseId());
    response.setAbcClass(policy.getAbcClass());
    response.setServiceLevel(policy.getServiceLevel());
    response.setLeadTimeDays(policy.getLeadTimeDays());
    response.setReviewPeriodDays(policy.getReviewPeriodDays());
    response.setSafetyDays(policy.getSafetyDays());
    response.setMinOrderQty(policy.getMinOrderQty());
    response.setPackMultiple(policy.getPackMultiple());
    response.setSupplierId(policy.getSupplierId());
    response.setManualOverride(policy.getManualOverride());
    response.setEnabled(policy.getEnabled());
    response.setRemark(policy.getRemark());
    response.setCreateTime(policy.getCreateTime());
    response.setUpdateTime(policy.getUpdateTime());
    return response;
  }
}
