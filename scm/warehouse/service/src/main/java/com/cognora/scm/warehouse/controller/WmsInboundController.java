package com.cognora.scm.warehouse.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.scm.warehouse.domain.entity.WmsInbound;
import com.cognora.scm.warehouse.service.command.WmsInboundCommandService;
import com.cognora.scm.warehouse.service.query.WmsInboundQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/wms-inbound")
@Tag(name = "入库管理", description = "仓库入库单的创建、查询、收货与取消管理")
public class WmsInboundController {

    private final WmsInboundCommandService inboundCommandService;
    private final WmsInboundQueryService inboundQueryService;

    @Operation(summary = "创建入库单", description = "根据仓库与入库类型新建一张入库单并生成入库单号。")
    @PostMapping
    @PreAuthorize("hasAuthority('warehouse:inbound:create')")
    @AuditLog(operation = "创建入库单", businessType = "WAREHOUSE", riskLevel = 3)
    public ApiResponse<WmsInbound> create(@RequestBody WmsInbound inbound) {
        log.info("[API] 创建入库单 warehouseId={}, type={}", (inbound.getWarehouseId() + "").replace("\r", "_").replace("\n", "_"), (inbound.getInboundType() + "").replace("\r", "_").replace("\n", "_"));
        WmsInbound created = inboundCommandService.create(inbound);
        log.info("[API] 入库单创建成功 id={}, inboundNo={}", (created.getId() + "").replace("\r", "_").replace("\n", "_"), (created.getInboundNo() + "").replace("\r", "_").replace("\n", "_"));
        return ApiResponse.success(created);
    }

    @Operation(summary = "更新入库单", description = "按主键更新入库单信息，仅允许在未收货状态下修改。")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:inbound:update')")
    @AuditLog(operation = "更新入库单", businessType = "WAREHOUSE", riskLevel = 3)
    public ApiResponse<WmsInbound> update(@Parameter(description = "入库单主键 ID") @PathVariable String id, @RequestBody WmsInbound inbound) {
        log.info("[API] 更新入库单 id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        inbound.setId(id);
        try {
            boolean success = inboundCommandService.update(inbound);
            if (!success) {
                return ApiResponse.fail(404, "入库单不存在");
            }
            return ApiResponse.success(inboundQueryService.getById(id));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @Operation(summary = "删除入库单", description = "按主键逻辑删除入库单，已删除的单据不再对外可见。")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:inbound:delete')")
    @AuditLog(operation = "删除入库单", businessType = "WAREHOUSE", riskLevel = 4)
    public ApiResponse<Void> delete(@Parameter(description = "入库单主键 ID") @PathVariable String id) {
        log.info("[API] 删除入库单 id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        boolean success = inboundCommandService.softDeleteById(id);
        return success ? ApiResponse.success() : ApiResponse.fail(404, "入库单不存在");
    }

    @Operation(summary = "查询入库单详情", description = "按主键查询入库单，单据不存在或已删除时返回 404。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('warehouse:inbound:page', 'warehouse:inbound:view')")
