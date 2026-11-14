package com.cognora.platform.inventory.dto;

import com.cognora.platform.inventory.domain.entity.InvStocktake;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 盘点单据响应（含明细）。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class StocktakeResponse {

  private String id;

  private String stocktakeNo;

  private String warehouseId;

  private String locationCode;

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

  private LocalDateTime createTime;

  private List<StocktakeItemResponse> items;

  public static StocktakeResponse from(InvStocktake head, List<StocktakeItemResponse> items) {
    StocktakeResponse response = new StocktakeResponse();
    response.setId(head.getId());
    response.setStocktakeNo(head.getStocktakeNo());
    response.setWarehouseId(head.getWarehouseId());
    response.setLocationCode(head.getLocationCode());
    response.setStatus(head.getStatus());
    response.setSnapshotAt(head.getSnapshotAt());
    response.setItemCount(head.getItemCount());
    response.setDiffCount(head.getDiffCount());
    response.setDiffQuantity(head.getDiffQuantity());
    response.setRemark(head.getRemark());
    response.setCreatedBy(head.getCreatedBy());
    response.setSubmittedBy(head.getSubmittedBy());
    response.setApprovedBy(head.getApprovedBy());
    response.setPostedBy(head.getPostedBy());
    response.setSubmitTime(head.getSubmitTime());
    response.setApproveTime(head.getApproveTime());
    response.setPostTime(head.getPostTime());
    response.setCreateTime(head.getCreateTime());
    response.setItems(items);
    return response;
  }
}
