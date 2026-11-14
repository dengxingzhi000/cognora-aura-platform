package com.cognora.platform.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.common.response.ResultCode;
import com.cognora.platform.inventory.domain.entity.InvAlert;
import com.cognora.platform.inventory.dto.InvAlertQueryRequest;
import com.cognora.platform.inventory.dto.InvAlertResponse;
import com.cognora.platform.inventory.dto.InvAlertScanResult;
import com.cognora.platform.inventory.dto.InvAlertStatsResponse;
import com.cognora.platform.inventory.service.command.InvAlertCommandService;
import com.cognora.platform.inventory.service.query.InvAlertQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存预警控制器
 *
 * <p>提供告警分页查询、统计、处理以及手动触发扫描的接口。
 *
 * @author SCM Platform Team
 * @since 2026-10-29
 */
@RequiredArgsConstructor
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/inventory/alerts")
@Tag(name = "库存预警", description = "提供库存告警查询、统计、处理与扫描接口。")
public class InvAlertController {

  private final InvAlertQueryService queryService;
  private final InvAlertCommandService commandService;

  /**
   * 分页查询告警
   */
  @Operation(summary = "分页查询告警", description = "按 SKU、仓库、告警类型和解决状态分页查询库存告警。")
  @PostMapping("/query")
  @PreAuthorize("hasAuthority('inventory:alert:query')")
  @AuditLog(operation = "分页查询库存告警", businessType = "INVENTORY", riskLevel = 2)
  public ApiResponse<Page<InvAlertResponse>> query(
      @RequestBody @Valid InvAlertQueryRequest request) {
    return ApiResponse.success(queryService.query(request));
  }

  /**
   * 查询告警统计
   */
  @Operation(summary = "查询告警统计", description = "统计告警总数、未解决数量以及库存不足与缺货告警数量。")
  @GetMapping("/stats")
  @PreAuthorize("hasAnyAuthority('inventory:alert:view', 'inventory:alert:stats')")
