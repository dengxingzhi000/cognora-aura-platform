package com.cognora.platform.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.common.response.ResultCode;
import com.cognora.platform.inventory.domain.entity.InvSnapshot;
import com.cognora.platform.inventory.dto.InvSnapshotGenerationResult;
import com.cognora.platform.inventory.dto.InvSnapshotQueryRequest;
import com.cognora.platform.inventory.service.command.InvSnapshotCommandService;
import com.cognora.platform.inventory.service.query.InvSnapshotQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 库存快照控制器
 *
 * <p>提供每日库存快照的查询、趋势与手动生成接口。
 *
 * @author SCM Platform Team
 * @since 2026-10-29
 */
@RequiredArgsConstructor
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/inventory/snapshots")
@Tag(name = "库存快照", description = "提供库存每日快照的查询、趋势与生成接口。")
public class InvSnapshotController {

  private final InvSnapshotQueryService queryService;
  private final InvSnapshotCommandService commandService;

  /**
   * 分页查询快照
   */
  @Operation(summary = "分页查询快照", description = "按日期、SKU 和仓库分页查询库存每日快照。")
  @PostMapping("/query")
  @PreAuthorize("hasAuthority('inventory:snapshot:query')")
  @AuditLog(operation = "分页查询库存快照", businessType = "INVENTORY", riskLevel = 2)
  public ApiResponse<Page<InvSnapshot>> query(
      @RequestBody @Valid InvSnapshotQueryRequest request) {
    return ApiResponse.success(queryService.query(request));
  }

  /**
   * 查询库存水位趋势
   */
  @Operation(summary = "查询库存趋势", description = "查询指定 SKU/仓库最近若干天的库存快照，用于绘制水位趋势。")
  @GetMapping("/trend")
  @PreAuthorize("hasAnyAuthority('inventory:snapshot:view', 'inventory:snapshot:trend')")
