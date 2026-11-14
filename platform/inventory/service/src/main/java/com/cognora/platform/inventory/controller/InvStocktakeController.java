package com.cognora.platform.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.inventory.dto.StocktakeCountRequest;
import com.cognora.platform.inventory.dto.StocktakeCreateRequest;
import com.cognora.platform.inventory.dto.StocktakeItemResponse;
import com.cognora.platform.inventory.dto.StocktakeQueryRequest;
import com.cognora.platform.inventory.dto.StocktakeResponse;
import com.cognora.platform.inventory.service.command.InvStocktakeCommandService;
import com.cognora.platform.inventory.service.query.StocktakeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存盘点控制器。
 *
 * <p>单据流转：DRAFT（创建并冻结账面）→ 录入实盘 → SUBMITTED →
 * APPROVED/REJECTED → POSTED（差异落账）。落账复用库存调整路径，
 * 与手工盘点同源。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/inventory/stocktakes")
@Tag(name = "库存盘点", description = "盘点单据的创建、录入、审批、落账与查询。")
public class InvStocktakeController {

  private final InvStocktakeCommandService commandService;
  private final StocktakeQueryService queryService;

  @Operation(summary = "创建盘点单",
      description = "按仓库/库位/SKU 范围创建盘点单，并冻结创建时点的账面数量。")
  @PostMapping
  public ApiResponse<StocktakeResponse> create(
      @RequestBody @Valid StocktakeCreateRequest request) {
    return ApiResponse.success(commandService.create(request));
  }

  @Operation(summary = "分页查询盘点单", description = "按仓库、状态与单号分页查询盘点单据头。")
  @PostMapping("/query")
  public ApiResponse<Page<StocktakeResponse>> query(
      @RequestBody @Valid StocktakeQueryRequest request) {
    return ApiResponse.success(queryService.query(request));
  }

  @Operation(summary = "盘点单详情", description = "返回盘点单据头与全部明细。")
  @GetMapping("/{id}")
  public ApiResponse<StocktakeResponse> detail(
      @Parameter(description = "盘点单 ID") @PathVariable @NotBlank(message = "盘点单 ID 不能为空")
      String id) {
    return ApiResponse.success(commandService.detail(id));
  }

  @Operation(summary = "盘点单明细", description = "单独读取盘点明细，便于大单据分页展示。")
  @GetMapping("/{id}/items")
  public ApiResponse<List<StocktakeItemResponse>> items(
      @Parameter(description = "盘点单 ID") @PathVariable @NotBlank(message = "盘点单 ID 不能为空")
      String id) {
    return ApiResponse.success(queryService.items(id));
  }

  @Operation(summary = "录入实盘数量", description = "仅草稿状态可录入，录入后自动计算差异。")
  @PutMapping("/{id}/count")
  public ApiResponse<StocktakeResponse> count(
      @Parameter(description = "盘点单 ID") @PathVariable @NotBlank(message = "盘点单 ID 不能为空")
      String id,
      @RequestBody @Valid StocktakeCountRequest request) {
    return ApiResponse.success(commandService.count(id, request));
  }

  @Operation(summary = "提交盘点单", description = "全部明细录入完成后提交，进入待审批。")
  @PutMapping("/{id}/submit")
  public ApiResponse<StocktakeResponse> submit(
      @Parameter(description = "盘点单 ID") @PathVariable @NotBlank(message = "盘点单 ID 不能为空")
      String id,
      @RequestParam(required = false) String operatorId) {
    return ApiResponse.success(commandService.submit(id, operatorId));
  }

  @Operation(summary = "审批通过", description = "审批已提交的盘点单，通过后允许落账。")
  @PutMapping("/{id}/approve")
  public ApiResponse<StocktakeResponse> approve(
      @Parameter(description = "盘点单 ID") @PathVariable @NotBlank(message = "盘点单 ID 不能为空")
      String id,
      @RequestParam(required = false) String operatorId) {
    return ApiResponse.success(commandService.approve(id, operatorId));
  }

  @Operation(summary = "驳回盘点单", description = "驳回已提交的盘点单，单据进入终态 REJECTED。")
  @PutMapping("/{id}/reject")
  public ApiResponse<StocktakeResponse> reject(
      @Parameter(description = "盘点单 ID") @PathVariable @NotBlank(message = "盘点单 ID 不能为空")
      String id,
      @RequestParam(required = false) String operatorId,
      @RequestParam(required = false) String reason) {
    return ApiResponse.success(commandService.reject(id, operatorId, reason));
  }

  @Operation(summary = "取消盘点单", description = "取消尚未落账的盘点单。")
  @PutMapping("/{id}/cancel")
  public ApiResponse<StocktakeResponse> cancel(
      @Parameter(description = "盘点单 ID") @PathVariable @NotBlank(message = "盘点单 ID 不能为空")
      String id,
      @RequestParam(required = false) String operatorId) {
    return ApiResponse.success(commandService.cancel(id, operatorId));
  }

  @Operation(summary = "盘点差异落账",
      description = "仅已审批单据可落账：按差异逐条写库存调整与流水，成功后单据进入 POSTED。")
  @PutMapping("/{id}/post")
  public ApiResponse<StocktakeResponse> post(
      @Parameter(description = "盘点单 ID") @PathVariable @NotBlank(message = "盘点单 ID 不能为空")
      String id,
      @RequestParam(required = false) String operatorId) {
    return ApiResponse.success(commandService.post(id, operatorId));
  }
}
