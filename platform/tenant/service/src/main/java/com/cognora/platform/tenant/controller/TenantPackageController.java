package com.cognora.platform.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.tenant.domain.entity.TenantPackage;
import com.cognora.platform.tenant.service.ITenantPackageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/tenant-package")
@Tag(name = "租户套餐", description = "提供租户套餐的维护、启用套餐查询与分页查询接口。")
public class TenantPackageController {
    private final ITenantPackageService tenantPackageService;

    @Operation(summary = "创建租户套餐", description = "新增一个租户套餐并返回创建后的套餐信息。")
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:package:create')")
    @AuditLog(operation = "创建租户套餐", businessType = "TENANT", riskLevel = 3)
    public TenantPackage create(@RequestBody TenantPackage entity) {
        log.info("[API] 创建租户套餐: packageCode={}, packageName={}", (entity.getPackageCode() + "").replace("\r", "_").replace("\n", "_"), (entity.getPackageName() + "").replace("\r", "_").replace("\n", "_"));
        return tenantPackageService.createPackage(entity);
    }

    @Operation(summary = "查询套餐详情", description = "根据套餐 ID 查询租户套餐详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('tenant:package:page', 'tenant:package:view')")
