package com.cognora.platform.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * SKU×仓日需求预测（inv_demand_forecast）。
 *
 * <p>{@code confidence=LOW_CONFIDENCE} 表示历史样本不足（冷启动）：此时预测值仍写库，
 * 但建议单会带上 {@code NEEDS_MANUAL} 标记，要求人工确认后才能采纳。
 *
 * <p>{@code version} 与 {@code forecastDate} 一起构成唯一键，保留同一日多次重算的历史，
 * 便于事后比较「当时按哪版预测补的货」。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
@TableName("inv_demand_forecast")
public class InvDemandForecast {

  /** 预测方法：规则版加权移动平均。 */
  public static final String METHOD_WEIGHTED_MOVING_AVERAGE = "WEIGHTED_MOVING_AVERAGE";

  /** 置信度：正常。 */
  public static final String CONFIDENCE_NORMAL = "NORMAL";

  /** 置信度：冷启动/样本不足，需人工确认。 */
  public static final String CONFIDENCE_LOW = "LOW_CONFIDENCE";

  @TableField(value = "tenant_id", fill = FieldFill.INSERT)
  private String tenantId;

  @TableId(type = IdType.ASSIGN_UUID)
  private String id;

  private String skuId;

  private String warehouseId;

  private LocalDate forecastDate;

  /** 预测日需求 μ */
  private BigDecimal dailyDemand;

  /** 需求标准差 σ */
  private BigDecimal demandStdDev;

  /** 回测误差；规则版移动平均为 null */
  private BigDecimal mape;

  private String method;

  private String confidence;

  /** 参与计算的历史天数 */
  private Integer sampleDays;

  private Integer version;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createTime;

  @TableLogic
  private Boolean deleted;
}
