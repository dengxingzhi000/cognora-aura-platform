package com.cognora.platform.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.inventory.dto.FefoPreviewRequest;
import com.cognora.platform.inventory.dto.FefoPreviewResponse;
import com.cognora.platform.inventory.dto.InvBatchQueryRequest;
import com.cognora.platform.inventory.dto.InvBatchResponse;
import com.cognora.platform.inventory.dto.InvBatchStatsResponse;
import com.cognora.platform.inventory.service.batch.FefoAllocationPlan;
import com.cognora.platform.inventory.service.batch.FefoAllocationService;
import com.cognora.platform.inventory.service.query.InvBatchQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 库存批次接口。
 *
 * <p>提供只读的批次/效期报表（分页与汇总）以及 FEFO 预览，供前端在下单前
 * 查看会扣减哪些批次、以及在运营侧盘点临期/过期批次；
 * 实际扣减仍在库存调整/调拨的出库路径内完成。
 */
@RestController
@RequestMapping("/api/v1/inventory/batches")
@RequiredArgsConstructor
@Tag(name = "库存批次", description = "提供批次效期报表与 FEFO 分配预览。")
public class InvBatchController {

  private final FefoAllocationService fefoAllocationService;
  private final InvBatchQueryService batchQueryService;

  @Operation(summary = "FEFO 分配预览",
      description = "只读预览：按先到期先出给出会扣减的批次与数量，不实际扣减库存。")
  @PostMapping("/fefo-preview")
  @AuditLog(operation = "预览 FEFO 批次分配", businessType = "INVENTORY", riskLevel = 1)
  public ApiResponse<FefoPreviewResponse> preview(@Valid @RequestBody FefoPreviewRequest request) {
    UUID tenantId = TenantContextHolder.getRequiredTenantId();
    FefoAllocationPlan plan = fefoAllocationService.preview(
        tenantId.toString(), request.getSkuId(), request.getWarehouseId(), request.getQuantity());
    return ApiResponse.success(FefoPreviewResponse.from(request, plan));
  }

  @Operation(summary = "分页查询批次",
      description = "按 SKU、仓库、批次号、质量状态、批次状态与效期区间分页查询批次，附剩余天数与剩余金额。")
  @PostMapping("/query")
  @AuditLog(operation = "分页查询库存批次", businessType = "INVENTORY", riskLevel = 2)
  public ApiResponse<Page<InvBatchResponse>> query(
      @RequestBody @Valid InvBatchQueryRequest request) {
    return ApiResponse.success(batchQueryService.query(request));
  }

  @Operation(summary = "批次效期汇总",
      description = "汇总同一筛选条件下的过期/临期/耗尽批次数量与剩余金额，用于报表 KPI。")
  @PostMapping("/stats")
  @AuditLog(operation = "汇总库存批次效期", businessType = "INVENTORY", riskLevel = 2)
  public ApiResponse<InvBatchStatsResponse> stats(
      @RequestBody @Valid InvBatchQueryRequest request) {
    return ApiResponse.success(batchQueryService.stats(request));
  }
}
