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
 * 盘点单据头（inv_stocktake）。
 *
 * <p>{@code snapshotAt} 是账面数冻结时点，明细的 book_* 取自该时点的台账，
 * 之后的库存变动不会回写本单据，保证盘点口径可复核。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
@TableName("inv_stocktake")
public class InvStocktake {

  @TableField(value = "tenant_id", fill = FieldFill.INSERT)
  private String tenantId;

  @TableId(type = IdType.ASSIGN_UUID)
  private String id;

  private String stocktakeNo;

  private String warehouseId;

  private String locationCode;

  /** DRAFT / SUBMITTED / APPROVED / POSTED / REJECTED / CANCELLED */
  private String status;

  private LocalDateTime snapshotAt;

  private Integer itemCount;

  private Integer diffCount;

  private Long diffQuantity;

  private String remark;

  private String createdBy;

  private String submittedBy;

  private String approvedBy;

  private String postedBy;

  private LocalDateTime submitTime;

  private LocalDateTime approveTime;

  private LocalDateTime postTime;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updateTime;

  @TableLogic
  private Boolean deleted;
}
