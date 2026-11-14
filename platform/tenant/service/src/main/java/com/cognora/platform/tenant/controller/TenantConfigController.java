package com.cognora.platform.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.tenant.domain.entity.TenantConfig;
import com.cognora.platform.tenant.service.ITenantConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/tenant-config")
@Tag(name = "租户配置", description = "提供租户级配置项的新增、查询、更新、删除与分页查询接口。")
public class TenantConfigController {

    private final ITenantConfigService tenantConfigService;

    @Operation(summary = "创建租户配置", description = "新增一条租户配置项并返回创建后的配置内容。")
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:config:create')")
    @AuditLog(operation = "创建租户配置", businessType = "TENANT", riskLevel = 3)
    public TenantConfig create(@RequestBody TenantConfig entity) {
        log.info("[API] 创建租户配置: tenantId={}, configKey={}", (entity.getTenantId() + "").replace("\r", "_").replace("\n", "_"), (entity.getConfigKey() + "").replace("\r", "_").replace("\n", "_"));
        return tenantConfigService.createConfig(entity);
    }

    @Operation(summary = "查询配置详情", description = "根据配置 ID 查询租户配置详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('tenant:config:page', 'tenant:config:view')")
