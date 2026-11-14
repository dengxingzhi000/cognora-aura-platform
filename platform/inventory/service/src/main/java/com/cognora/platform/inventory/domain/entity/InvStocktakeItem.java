package com.cognora.platform.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点明细（inv_stocktake_item）。
 *
 * <p>{@code countedQuantity} 为空表示尚未盘；{@code diffQuantity} 由服务层在
 * 录入实盘数时写入（实盘 - 账面可用），落账时再以它驱动库存调整。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
@TableName("inv_stocktake_item")
public class InvStocktakeItem {

  @TableField(value = "tenant_id", fill = FieldFill.INSERT)
  private String tenantId;

  @TableId(type = IdType.ASSIGN_UUID)
  private String id;

  private String stocktakeId;

  private String skuId;

  private String warehouseId;

  private String locationCode;

  /** 冻结的账面数 */
  private Integer bookTotal;

  private Integer bookAvailable;

  private Integer bookLocked;

  private Integer countedQuantity;

  /** 差异 = countedQuantity - bookAvailable */
  private Integer diffQuantity;

  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updateTime;

  @TableLogic
  private Boolean deleted;
}
