package com.cognora.scm.warehouse.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.scm.warehouse.domain.entity.WmsWavePicking;
import com.cognora.scm.warehouse.service.command.WmsWavePickingCommandService;
import com.cognora.scm.warehouse.service.query.WmsWavePickingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/wms-wave-picking")
@Tag(name = "波次拣货", description = "波次拣货单的创建、查询、拣货执行与取消管理")
public class WmsWavePickingController {

    private final WmsWavePickingCommandService wavePickingCommandService;
    private final WmsWavePickingQueryService wavePickingQueryService;

    @Operation(summary = "创建波次拣货单", description = "按仓库与订单数量新建波次拣货单并生成波次号。")
    @PostMapping
    @PreAuthorize("hasAuthority('warehouse:wave-picking:create')")
    @AuditLog(operation = "创建波次拣货单", businessType = "WAREHOUSE", riskLevel = 3)
    public ApiResponse<WmsWavePicking> create(@RequestBody WmsWavePicking wave) {
        log.info("[API] 创建波次拣货单 warehouseId={}, orderCount={}", (wave.getWarehouseId() + "").replace("\r", "_").replace("\n", "_"), (wave.getOrderCount() + "").replace("\r", "_").replace("\n", "_"));
        WmsWavePicking created = wavePickingCommandService.create(wave);
        log.info("[API] 波次拣货单创建成功 id={}, waveNo={}", (created.getId() + "").replace("\r", "_").replace("\n", "_"), (created.getWaveNo() + "").replace("\r", "_").replace("\n", "_"));
        return ApiResponse.success(created);
    }

    @Operation(summary = "更新波次拣货单", description = "按主键更新波次拣货单信息，仅未开始拣货的波次允许修改。")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:wave-picking:update')")
    @AuditLog(operation = "更新波次拣货单", businessType = "WAREHOUSE", riskLevel = 3)
    public ApiResponse<WmsWavePicking> update(@Parameter(description = "波次拣货单主键 ID") @PathVariable String id, @RequestBody WmsWavePicking wave) {
        log.info("[API] 更新波次拣货单 id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        wave.setId(id);
        try {
            boolean success = wavePickingCommandService.update(wave);
            if (!success) {
                return ApiResponse.fail(404, "波次拣货单不存在");
            }
            return ApiResponse.success(wavePickingQueryService.getById(id));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @Operation(summary = "删除波次拣货单", description = "按主键删除波次拣货单，删除后不再参与拣货作业。")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:wave-picking:delete')")
    @AuditLog(operation = "删除波次拣货单", businessType = "WAREHOUSE", riskLevel = 4)
    public ApiResponse<Void> delete(@Parameter(description = "波次拣货单主键 ID") @PathVariable String id) {
        log.info("[API] 删除波次拣货单 id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        boolean success = wavePickingCommandService.removeById(id);
        return success ? ApiResponse.success() : ApiResponse.fail(404, "波次拣货单不存在");
    }

    @Operation(summary = "查询波次拣货单详情", description = "按主键查询波次拣货单，单据不存在时返回 404。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('warehouse:wave-picking:page', 'warehouse:wave-picking:view')")
