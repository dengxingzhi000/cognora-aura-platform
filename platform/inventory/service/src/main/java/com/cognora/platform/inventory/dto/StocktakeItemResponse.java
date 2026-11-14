package com.cognora.platform.inventory.dto;

import com.cognora.platform.inventory.domain.entity.InvStocktakeItem;
import lombok.Data;

/**
 * 盘点明细响应。
 *
 * <p>{@code diffQuantity} 在录入实盘数前为空；{@code settled} 表示该行差异
 * 已在落账时写回台账，避免重复落账。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class StocktakeItemResponse {

  private String id;

  private String skuId;

  private String warehouseId;

  private String locationCode;

  private Integer bookTotal;

  private Integer bookAvailable;

  private Integer bookLocked;

  private Integer countedQuantity;

  /** 差异 = 实盘 - 账面可用；未盘时为空 */
  private Integer diffQuantity;

  private String remark;

  public static StocktakeItemResponse from(InvStocktakeItem item) {
    StocktakeItemResponse response = new StocktakeItemResponse();
    response.setId(item.getId());
    response.setSkuId(item.getSkuId());
    response.setWarehouseId(item.getWarehouseId());
    response.setLocationCode(item.getLocationCode());
    response.setBookTotal(item.getBookTotal());
    response.setBookAvailable(item.getBookAvailable());
    response.setBookLocked(item.getBookLocked());
    response.setCountedQuantity(item.getCountedQuantity());
    response.setDiffQuantity(item.getDiffQuantity());
    response.setRemark(item.getRemark());
    return response;
  }
}
