package com.cognora.platform.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 补货计算触发请求。
 *
 * <p>三个范围参数都是可选的：都不传表示按当前租户的全部库存台账算一遍；
 * 传了 {@code warehouseId} 就只算该仓。{@code dryRun=true} 只算不落库，
 * 用于上线前核对与回测。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentGenerateRequest {

  /** 只算指定仓库；为空表示当前租户所有仓库 */
  private String warehouseId;

  /** 只算指定 SKU 列表；为空表示该范围内全部 SKU */
  private java.util.List<String> skuIds;

  /** 补货周期，如 2026-W45；为空时按 ISO 周从当前日期推导 */
  private String period;

  /**
   * true 时只返回计算结果，不创建建议单。
   *
   * <p>注意：dry-run 仍然会写需求预测（它是可重复覆盖的推导数据），
   * 但不碰建议单与它的幂等键。
   */
  private Boolean dryRun;

  /** 本周期可用预算；为空表示不按预算裁剪 */
  private java.math.BigDecimal totalBudget;

  /** 历史回看天数，决定移动平均窗口；为空时用默认值 */
  @Min(value = 1, message = "回看天数最小为 1")
  @Max(value = 365, message = "回看天数最大为 365")
  private Integer lookbackDays;

  private String operatorId;
}
