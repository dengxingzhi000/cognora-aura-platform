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
    @PreAuthorize("hasAnyAuthority('tenant:tenant:page', 'tenant:tenant:view')")
