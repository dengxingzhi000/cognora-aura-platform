package com.cognora.platform.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.inventory.dto.InventoryValuationQueryRequest;
import com.cognora.platform.inventory.dto.InventoryValuationRow;
import com.cognora.platform.inventory.dto.InventoryValuationStats;
import com.cognora.platform.inventory.service.query.InventoryValuationQueryService;
import com.cognora.platform.inventory.service.report.InventoryValuationCsvWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 库存估值报表控制器。
 *
 * <p>提供分页、汇总与 CSV 导出。导出走 {@code text/csv} + UTF-8 BOM，
 * 文件内容经过 CSV 注入防护，详见 {@link InventoryValuationCsvWriter}。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/inventory/valuation")
@Tag(name = "库存估值", description = "提供库存金额口径的分页、汇总与 CSV 导出。")
public class InventoryValuationController {

  /** 单次导出行数上限：防止一个筛选条件把整库导出耗尽内存与响应带宽。 */
  private static final int EXPORT_MAX_ROWS = 20000;

  private final InventoryValuationQueryService queryService;

  @Operation(summary = "分页查询库存估值",
      description = "按 SKU、仓库、库位与库存状态分页查询，返回数量、金额与呆滞天数。")
  @PostMapping("/query")
  public ApiResponse<Page<InventoryValuationRow>> query(
      @RequestBody @Valid InventoryValuationQueryRequest request) {
    return ApiResponse.success(queryService.page(request));
  }

  @Operation(summary = "库存估值汇总",
      description = "汇总同一筛选条件下的数量与金额，可选按呆滞天数统计呆滞占用。")
  @PostMapping("/stats")
  public ApiResponse<InventoryValuationStats> stats(
      @RequestBody @Valid InventoryValuationQueryRequest request) {
    return ApiResponse.success(queryService.stats(request));
  }

  @Operation(summary = "导出库存估值 CSV",
      description = "按筛选条件导出 CSV（UTF-8 BOM），单次最多 " + EXPORT_MAX_ROWS + " 行。")
  @PostMapping("/export")
  public ResponseEntity<byte[]> export(
      @RequestBody @Valid InventoryValuationQueryRequest request) {
    // 导出是“全量”语义，忽略分页参数但沿用其余筛选条件
    request.setPage(1);
    request.setSize(EXPORT_MAX_ROWS);

    List<InventoryValuationRow> rows = queryService.list(request);
    byte[] body = InventoryValuationCsvWriter.writeBytes(rows, LocalDateTime.now());

    String filename = "inventory-valuation-"
        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
    headers.setContentDisposition(ContentDisposition.attachment()
        .filename(filename, StandardCharsets.UTF_8)
        .build());
    headers.set("X-Content-Type-Options", "nosniff");
    return ResponseEntity.ok().headers(headers).body(body);
  }
}
