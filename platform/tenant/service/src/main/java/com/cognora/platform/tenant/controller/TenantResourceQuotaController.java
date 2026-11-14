package com.cognora.platform.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.tenant.domain.entity.TenantResourceQuota;
import com.cognora.platform.tenant.service.ITenantResourceQuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/tenant-resource-quota")
@Tag(name = "租户资源配额", description = "提供租户资源配额的维护、配额余量校验与分页查询接口。")
public class TenantResourceQuotaController {
    private final ITenantResourceQuotaService tenantResourceQuotaService;

    @Operation(summary = "创建资源配额", description = "为指定租户新增资源配额记录并返回创建结果。")
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:quota:create')")
    @AuditLog(operation = "创建租户资源配额", businessType = "TENANT", riskLevel = 3)
    public TenantResourceQuota create(@RequestBody TenantResourceQuota entity) {
        log.info("[API] Create tenant resource quota: tenantId={}", (entity.getTenantId() + "").replace("\r", "_").replace("\n", "_"));
        return tenantResourceQuotaService.createQuota(entity);
    }

    @Operation(summary = "查询配额详情", description = "根据配额 ID 查询租户资源配额详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('tenant:quota:page', 'tenant:quota:view')")
