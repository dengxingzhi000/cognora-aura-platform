package com.cognora.platform.inventory.dto;

import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 补货建议单响应（列表不带明细，详情带）。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Data
public class ReplenishmentSuggestionResponse {

  private String id;
  private String suggestionNo;
  private String warehouseId;
  private String period;
  private String status;
  private String businessKey;
  private Integer itemCount;
  private Long totalSuggestedQty;
  private Long totalAdoptedQty;

  /** 转换后的采购计划；未转换时为空 */
  private String purPlanId;
  private String purPlanNo;

  /** 转换失败原因；成功或未转换时为空 */
  private String convertError;
  private String rejectReason;
  private String remark;
  private String createdBy;
  private String adoptedBy;
  private LocalDateTime adoptedAt;
  private LocalDateTime convertedAt;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;

  /** 仅详情接口返回 */
  private List<ReplenishmentSuggestionItemResponse> items;

  public static ReplenishmentSuggestionResponse from(InvReplenishmentSuggestion head,
                                                     List<ReplenishmentSuggestionItemResponse> items) {
    ReplenishmentSuggestionResponse response = new ReplenishmentSuggestionResponse();
    response.setId(head.getId());
    response.setSuggestionNo(head.getSuggestionNo());
    response.setWarehouseId(head.getWarehouseId());
    response.setPeriod(head.getPeriod());
    response.setStatus(head.getStatus());
    response.setBusinessKey(head.getBusinessKey());
    response.setItemCount(head.getItemCount());
    response.setTotalSuggestedQty(head.getTotalSuggestedQty());
    response.setTotalAdoptedQty(head.getTotalAdoptedQty());
    response.setPurPlanId(head.getPurPlanId());
    response.setPurPlanNo(head.getPurPlanNo());
    response.setConvertError(head.getConvertError());
    response.setRejectReason(head.getRejectReason());
    response.setRemark(head.getRemark());
    response.setCreatedBy(head.getCreatedBy());
    response.setAdoptedBy(head.getAdoptedBy());
    response.setAdoptedAt(head.getAdoptedAt());
    response.setConvertedAt(head.getConvertedAt());
    response.setCreateTime(head.getCreateTime());
    response.setUpdateTime(head.getUpdateTime());
    response.setItems(items);
    return response;
  }
}
