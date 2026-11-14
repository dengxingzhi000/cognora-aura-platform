package com.cognora.lms.logistics.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.lms.logistics.domain.entity.TmsCarrier;
import com.cognora.lms.logistics.service.ITmsCarrierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/v1/tms-carrier")
@Tag(name = "承运商管理", description = "运输承运商档案的维护、启用停用及分页查询接口。")
public class TmsCarrierController {

    private final ITmsCarrierService carrierService;

    @Operation(summary = "查询承运商详情", description = "根据承运商主键 ID 获取承运商完整信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('logistics:carrier:view', 'logistics:carrier:list-enabled', 'logistics:carrier:page')")
    public ApiResponse<TmsCarrier> getById(
            @Parameter(description = "承运商主键 ID") @PathVariable String id) {
        TmsCarrier carrier = carrierService.getById(id);
        return ApiResponse.success(carrier);
    }

    @Operation(summary = "按编码查询承运商", description = "根据承运商编码获取对应的承运商信息。")
    @GetMapping("/code/{carrierCode}")
    @PreAuthorize("hasAuthority('logistics:carrier:get-by-carrier-code')")
    public ApiResponse<TmsCarrier> getByCarrierCode(
            @Parameter(description = "承运商编码") @PathVariable String carrierCode) {
        TmsCarrier carrier = carrierService.getByCarrierCode(carrierCode);
        return ApiResponse.success(carrier);
    }

    @Operation(summary = "分页查询承运商", description = "按承运商名称、类型和启用状态分页查询承运商列表。")
    @GetMapping("/list")
    @PreAuthorize("hasAnyAuthority('logistics:carrier:page', 'logistics:carrier:view')")
    public ApiResponse<Page<TmsCarrier>> pageList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "承运商名称，支持模糊匹配") @RequestParam(required = false) String carrierName,
            @RequestParam(required = false) Integer carrierType,
            @Parameter(description = "是否启用，true 为启用、false 为停用") @RequestParam(required = false) Boolean enabled) {
        Page<TmsCarrier> result = carrierService.pageList(page, size, carrierName, carrierType, enabled);
        return ApiResponse.success(result);
    }

    @Operation(summary = "查询启用的承运商", description = "获取当前所有处于启用状态的承运商列表。")
    @GetMapping("/enabled")
    @PreAuthorize("hasAnyAuthority('logistics:carrier:list-enabled', 'logistics:carrier:view')")
    public ApiResponse<List<TmsCarrier>> listEnabled() {
        List<TmsCarrier> list = carrierService.listEnabled();
        return ApiResponse.success(list);
    }

    @Operation(summary = "新增承运商", description = "创建新的承运商档案并返回保存后的承运商信息。")
    @PostMapping
    @PreAuthorize("hasAuthority('logistics:carrier:create')")
    @AuditLog(operation = "新增承运商", businessType = "LOGISTICS", riskLevel = 3, recordParams = false)
    public ApiResponse<TmsCarrier> create(@RequestBody TmsCarrier carrier) {
        TmsCarrier created = carrierService.createCarrier(carrier);
        return ApiResponse.success(created);
    }

    @Operation(summary = "更新承运商", description = "根据主键 ID 更新承运商信息并返回更新后的数据。")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('logistics:carrier:update')")
    @AuditLog(operation = "更新承运商", businessType = "LOGISTICS", riskLevel = 3, recordParams = false)
    public ApiResponse<TmsCarrier> update(@Parameter(description = "承运商主键 ID") @PathVariable String id,
                                          @RequestBody TmsCarrier carrier) {
        TmsCarrier updated = carrierService.updateCarrier(id, carrier);
        return ApiResponse.success(updated);
    }

    @Operation(summary = "删除承运商", description = "根据主键 ID 逻辑删除指定承运商。")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('logistics:carrier:delete')")
    @AuditLog(operation = "删除承运商", businessType = "LOGISTICS", riskLevel = 4)
    public ApiResponse<Void> delete(@Parameter(description = "承运商主键 ID") @PathVariable String id) {
        carrierService.deleteCarrier(id);
        return ApiResponse.success();
    }

    @Operation(summary = "启用或停用承运商", description = "根据传入的启用标识切换指定承运商的启用状态。")
    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('logistics:carrier:toggle-enabled')")
    @AuditLog(operation = "启停承运商", businessType = "LOGISTICS", riskLevel = 3)
    public ApiResponse<Void> toggleEnabled(@Parameter(description = "承运商主键 ID") @PathVariable String id,
                                           @Parameter(description = "是否启用，true 为启用、false 为停用") @RequestParam boolean enabled) {
        carrierService.toggleEnabled(id, enabled);
        return ApiResponse.success();
    }
}