package com.cognora.platform.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 采纳/驳回/取消建议单的请求。
 *
 * <p>驳回必须带原因：没有原因的驳回既无法做反馈闭环，也无法向采购解释。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentDecisionRequest {

  /** 驳回/取消原因；adopt 时可省略 */
  private String reason;

  /** 操作人；由控制器从登录态取，不接受前端传 */
  private String operatorId;

  /** 驳回原因必填校验用（控制器层调用） */
  public boolean hasReason() {
    return reason != null && !reason.isBlank();
  }

  /** 供 Swagger/校验提示使用，实际校验在命令服务内。 */
  @NotBlank
  public String getReasonOrEmpty() {
    return reason == null ? "" : reason;
  }
}
