package com.cognora.platform.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.tenant.domain.entity.Tenant;
import com.cognora.platform.tenant.service.ITenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/tenant")
@Tag(name = "租户管理", description = "提供租户的创建、查询、更新、删除、启停与分页查询接口。")
public class TenantController {

    private final ITenantService tenantService;

    @Operation(summary = "创建租户", description = "新增一个租户并返回创建后的租户信息。")
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:tenant:create')")
    @AuditLog(operation = "创建租户", businessType = "TENANT", riskLevel = 4)
    public Tenant create(@RequestBody Tenant entity) {
        log.info("[API] 创建租户: tenantCode={}, tenantName={}", (entity.getTenantCode() + "").replace("\r", "_").replace("\n", "_"), (entity.getTenantName() + "").replace("\r", "_").replace("\n", "_"));
        return tenantService.createTenant(entity);
    }

    @Operation(summary = "查询租户详情", description = "根据租户 ID 查询租户详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:tenant:view')")
    public Tenant getById(@Parameter(description = "租户ID") @PathVariable String id) {
        log.info("[API] 查询租户: id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        return tenantService.getById(id);
    }

    @Operation(summary = "更新租户", description = "根据租户 ID 更新租户信息并返回更新结果。")
    @PutMapping
    @PreAuthorize("hasAuthority('tenant:tenant:update')")
    @AuditLog(operation = "更新租户", businessType = "TENANT", riskLevel = 3)
    public Tenant update(@RequestBody Tenant entity) {
        log.info("[API] 更新租户: id={}", (entity.getId() + "").replace("\r", "_").replace("\n", "_"));
        return tenantService.updateTenant(entity);
    }

    @Operation(summary = "删除租户", description = "根据租户 ID 删除指定租户。")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:tenant:delete')")
    @AuditLog(operation = "删除租户", businessType = "TENANT", riskLevel = 4)
    public boolean deleteById(@Parameter(description = "租户ID") @PathVariable String id) {
        log.info("[API] 删除租户: id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        return tenantService.deleteById(id);
    }

    @Operation(summary = "启用租户", description = "将指定租户状态置为可用，允许其正常访问平台。")
    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('tenant:tenant:enable')")
    @AuditLog(operation = "启用租户", businessType = "TENANT", riskLevel = 4)
    public boolean enable(@Parameter(description = "租户ID") @PathVariable String id) {
        log.info("[API] 启用租户: id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        return tenantService.enableTenant(id);
    }

    @Operation(summary = "禁用租户", description = "将指定租户状态置为禁用，暂停其访问平台。")
    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('tenant:tenant:disable')")
    @AuditLog(operation = "禁用租户", businessType = "TENANT", riskLevel = 4)
    public boolean disable(@Parameter(description = "租户ID") @PathVariable String id) {
        log.info("[API] 禁用租户: id={}", (id + "").replace("\r", "_").replace("\n", "_"));
        return tenantService.disableTenant(id);
    }

    @Operation(summary = "查询活跃租户列表", description = "查询当前状态为活跃的全部租户。")
    @GetMapping("/active")
    @PreAuthorize("hasAuthority('tenant:tenant:list-active')")
    public List<Tenant> listActive() {
        log.info("[API] 查询活跃租户");
        return tenantService.listActive();
    }

    @Operation(summary = "分页查询租户", description = "按名称、类型和状态分页查询租户列表。")
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('tenant:tenant:page')")
    public Page<Tenant> pageQuery(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "租户名称") @RequestParam(required = false) String tenantName,
            @Parameter(description = "租户类型") @RequestParam(required = false) Integer tenantType,
            @Parameter(description = "租户状态") @RequestParam(required = false) Integer status) {
        log.info("[API] 分页查询租户: page={}, size={}", page, size);
        return tenantService.pageQuery(page, size, tenantName, tenantType, status);
    }
}
