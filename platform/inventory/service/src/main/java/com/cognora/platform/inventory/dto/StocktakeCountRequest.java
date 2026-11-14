package com.cognora.platform.inventory.dto;

import lombok.Data;

import java.util.List;

/**
 * 录入实盘数请求：整单提交多条明细的实盘数量。
 *
 * <p>只允许在 DRAFT 状态录入；SUBMITTED 之后数量被锁定，防止审批期间数据漂移。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Data
public class StocktakeCountRequest {

  @jakarta.validation.constraints.NotEmpty(message = "盘点明细不能为空")
  private List<Item> items;

  private String operatorId;

  @Data
  public static class Item {

    @jakarta.validation.constraints.NotBlank(message = "SKU ID 不能为空")
    private String skuId;

    /** 实盘数量（>= 0） */
    @jakarta.validation.constraints.Min(value = 0, message = "实盘数量不能为负数")
    private Integer countedQuantity;

    private String remark;
  }
}
