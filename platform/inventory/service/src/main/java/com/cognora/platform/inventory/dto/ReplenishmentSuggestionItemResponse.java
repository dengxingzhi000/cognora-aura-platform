package com.cognora.platform.inventory.dto;

import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 补货建议明细响应。
 *
 * <p>除了持久化字段，还回显 {@code effectiveQty}（采纳量优先）与
 * {@code adjusted}（是否被人工改过量），前端不需要自己再算一遍。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentSuggestionItemResponse {

  private String id;
  private String skuId;
  private String warehouseId;
  private Integer availableStock;
  private Integer inTransitQty;
  private BigDecimal reorderPoint;
  private BigDecimal safetyStock;
  private BigDecimal targetStock;
  private Integer suggestedQty;
  private Integer adoptedQty;
  private String supplierId;
  private String flags;
  private String reasonSnapshot;

  /** 最终要转出的量：采纳量优先，否则建议量 */
  private Integer effectiveQty;

  /** 是否被人工改过量 */
  private Boolean adjusted;

  public static ReplenishmentSuggestionItemResponse from(InvReplenishmentSuggestionItem item) {
    ReplenishmentSuggestionItemResponse response = new ReplenishmentSuggestionItemResponse();
    response.setId(item.getId());
    response.setSkuId(item.getSkuId());
    response.setWarehouseId(item.getWarehouseId());
    response.setAvailableStock(item.getAvailableStock());
    response.setInTransitQty(item.getInTransitQty());
    response.setReorderPoint(item.getReorderPoint());
    response.setSafetyStock(item.getSafetyStock());
    response.setTargetStock(item.getTargetStock());
    response.setSuggestedQty(item.getSuggestedQty());
    response.setAdoptedQty(item.getAdoptedQty());
    response.setSupplierId(item.getSupplierId());
    response.setFlags(item.getFlags());
    response.setReasonSnapshot(item.getReasonSnapshot());
    response.setEffectiveQty(item.effectiveQty());
    response.setAdjusted(item.isQuantityAdjusted());
    return response;
  }
}
