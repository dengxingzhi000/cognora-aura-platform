package com.cognora.scm.warehouse.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.scm.warehouse.domain.entity.WmsOutbound;
import com.cognora.scm.warehouse.service.command.WmsOutboundCommandService;
import com.cognora.scm.warehouse.service.query.WmsOutboundQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/wms-outbound")
@Tag(name = "出库管理", description = "仓库出库单的创建、查询、发货与取消管理")
public class WmsOutboundController {

    private final WmsOutboundCommandService outboundCommandService;
    private final WmsOutboundQueryService outboundQueryService;

    @Operation(summary = "创建出库单", description = "根据仓库与出库类型新建一张出库单并生成出库单号。")
    @PostMapping
    @PreAuthorize("hasAuthority('warehouse:outbound:create')")
    @AuditLog(operation = "创建出库单", businessType = "WAREHOUSE", riskLevel = 3)
    public ApiResponse<WmsOutbound> create(@RequestBody WmsOutbound outbound) {
        log.info("[API] 创建出库单 warehouseId={}, type={}", (outbound.getWarehouseId() + "").replace("\r", "_").replace("\n", "_"), (outbound.getOutboundType() + "").replace("\r", "_").replace("\n", "_"));
        WmsOutbound created = outboundCommandService.create(outbound);
        log.info("[API] 出库单创建成功 id={}, outboundNo={}", (created.getId() + "").replace("\r", "_").replace("\n", "_"), (created.getOutboundNo() + "").replace("\r", "_").replace("\n", "_"));
        return ApiResponse.success(created);
    }

    @Operation(summary = "更新出库单", description = "按主键更新出库单信息，仅允许在未发货状态下修改。")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:outbound:update')")
    @AuditLog(operation = "更新出库单", businessType = "WAREHOUSE", riskLevel = 3)
    public ApiResponse<WmsOutbound> update(@Parameter(description = "出库单主键 ID") @PathVariable String id, @RequestBody WmsOutbound outbound) {
        log.info("[API] 更新出库单 id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        outbound.setId(id);
        try {
            boolean success = outboundCommandService.update(outbound);
            if (!success) {
                return ApiResponse.fail(404, "出库单不存在");
            }
            return ApiResponse.success(outboundQueryService.getById(id));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @Operation(summary = "删除出库单", description = "按主键逻辑删除出库单，已删除的单据不再对外可见。")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:outbound:delete')")
    @AuditLog(operation = "删除出库单", businessType = "WAREHOUSE", riskLevel = 4)
    public ApiResponse<Void> delete(@Parameter(description = "出库单主键 ID") @PathVariable String id) {
        log.info("[API] 删除出库单 id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        boolean success = outboundCommandService.softDeleteById(id);
        return success ? ApiResponse.success() : ApiResponse.fail(404, "出库单不存在");
    }

    @Operation(summary = "查询出库单详情", description = "按主键查询出库单，单据不存在或已删除时返回 404。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('warehouse:outbound:page', 'warehouse:outbound:view')")
