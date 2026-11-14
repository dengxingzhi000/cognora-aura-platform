package com.cognora.lms.logistics.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.lms.logistics.domain.entity.TmsRoute;
import com.cognora.lms.logistics.service.ITmsRouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/v1/tms-route")
@Tag(name = "运输路线", description = "配送运输路线档案的维护与按骑手、日期查询接口。")
public class TmsRouteController {

    private final ITmsRouteService routeService;

    @Operation(summary = "查询路线详情", description = "根据路线主键 ID 获取运输路线详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('logistics:route:view')")
    public ApiResponse<TmsRoute> getById(
            @Parameter(description = "路线主键 ID") @PathVariable String id) {
        TmsRoute route = routeService.getById(id);
        return ApiResponse.success(route);
    }

    @Operation(summary = "按路线编号查询", description = "根据路线编号获取对应的运输路线信息。")
    @GetMapping("/no/{routeNo}")
    @PreAuthorize("hasAuthority('logistics:route:get-by-route-no')")
    public ApiResponse<TmsRoute> getByRouteNo(
            @Parameter(description = "路线编号") @PathVariable String routeNo) {
        TmsRoute route = routeService.getByRouteNo(routeNo);
        return ApiResponse.success(route);
    }

    @Operation(summary = "分页查询路线", description = "按骑手、路线状态和配送日期分页查询运输路线列表。")
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('logistics:route:page')")
    public ApiResponse<Page<TmsRoute>> pageList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String courierId,
            @RequestParam(required = false) Integer status,
            @Parameter(description = "配送日期，格式 yyyy-MM-dd") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        Page<TmsRoute> result = routeService.pageList(page, size, courierId, status, deliveryDate);
        return ApiResponse.success(result);
    }

    @Operation(summary = "查询骑手的路线", description = "获取指定骑手负责的全部运输路线。")
    @GetMapping("/courier/{courierId}")
    @PreAuthorize("hasAuthority('logistics:route:list-by-courier-id')")
    public ApiResponse<List<TmsRoute>> listByCourierId(
            @Parameter(description = "骑手主键 ID") @PathVariable String courierId) {
        List<TmsRoute> list = routeService.listByCourierId(courierId);
        return ApiResponse.success(list);
    }

    @Operation(summary = "新增路线", description = "创建新的运输路线并返回保存后的路线信息。")
    @PostMapping
    @PreAuthorize("hasAuthority('logistics:route:create')")
    @AuditLog(operation = "新增运输路线", businessType = "LOGISTICS", riskLevel = 3)
    public ApiResponse<TmsRoute> create(@RequestBody TmsRoute route) {
        TmsRoute created = routeService.createRoute(route);
        return ApiResponse.success(created);
    }

    @Operation(summary = "更新路线", description = "根据主键 ID 更新运输路线信息并返回更新后的数据。")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('logistics:route:update')")
    @AuditLog(operation = "更新运输路线", businessType = "LOGISTICS", riskLevel = 3)
    public ApiResponse<TmsRoute> update(@Parameter(description = "路线主键 ID") @PathVariable String id, @RequestBody TmsRoute route) {
        TmsRoute updated = routeService.updateRoute(id, route);
        return ApiResponse.success(updated);
    }

    @Operation(summary = "删除路线", description = "根据主键 ID 删除指定运输路线。")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('logistics:route:delete')")
    @AuditLog(operation = "删除运输路线", businessType = "LOGISTICS", riskLevel = 4)
    public ApiResponse<Void> delete(@Parameter(description = "路线主键 ID") @PathVariable String id) {
        log.info("Delete route: id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        routeService.removeById(id);
        return ApiResponse.success();
    }
}
