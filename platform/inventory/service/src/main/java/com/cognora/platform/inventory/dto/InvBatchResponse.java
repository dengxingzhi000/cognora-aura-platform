package com.cognora.platform.inventory.dto;

import com.cognora.platform.inventory.domain.entity.InvBatch;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 批次报表行。
 *
 * <p>在 {@link InvBatch} 的持久化字段之外补充两个运营侧派生值：
 * {@code daysToExpiry}（距离效期天数，负数表示已过期）与
 * {@code remainingValue}（剩余可用量按批次单价计的金额），
 * 避免前端各自计算口径不一致。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class InvBatchResponse {

  private String id;

  private String batchCode;

  private String skuId;

  private String warehouseId;

  private String locationCode;

  private LocalDate productionDate;

  private LocalDate expiryDate;

  private Integer shelfLifeDays;

  /** 1-合格 2-待检 3-不合格 4-隔离 */
  private Integer qualityStatus;

  /** 0-已耗尽 1-正常 2-临期 3-已过期 */
  private Integer status;

  private Integer totalQuantity;

  private Integer availableQuantity;

  private Integer lockedQuantity;

  private Integer damagedQuantity;

  private BigDecimal unitCost;

  /** 距离效期天数：负数表示已过期，null 表示无有效期 */
  private Long daysToExpiry;

  /** 剩余可用量金额 */
  private BigDecimal remainingValue;

  private LocalDateTime createTime;

  private LocalDateTime updateTime;

  public static InvBatchResponse from(InvBatch batch, LocalDate today) {
    InvBatchResponse response = new InvBatchResponse();
    response.setId(batch.getId());
    response.setBatchCode(batch.getBatchCode());
    response.setSkuId(batch.getSkuId());
    response.setWarehouseId(batch.getWarehouseId());
    response.setProductionDate(batch.getProductionDate());
    response.setExpiryDate(batch.getExpiryDate());
    response.setShelfLifeDays(batch.getShelfLifeDays());
    response.setQualityStatus(batch.getQualityStatus());
    response.setStatus(batch.getStatus());
    response.setTotalQuantity(batch.getTotalQuantity());
    response.setAvailableQuantity(batch.getAvailableQuantity());
    response.setLockedQuantity(batch.getLockedQuantity());
    response.setDamagedQuantity(batch.getDamagedQuantity());
    response.setUnitCost(batch.getUnitCost());
    response.setCreateTime(batch.getCreateTime());
    response.setUpdateTime(batch.getUpdateTime());

    if (batch.getExpiryDate() != null) {
      response.setDaysToExpiry(
          java.time.temporal.ChronoUnit.DAYS.between(today, batch.getExpiryDate()));
    }
    int available = batch.getAvailableQuantity() == null ? 0 : batch.getAvailableQuantity();
    BigDecimal unitCost = batch.getUnitCost() == null ? BigDecimal.ZERO : batch.getUnitCost();
    response.setRemainingValue(unitCost.multiply(BigDecimal.valueOf(available)));
    return response;
  }
}
