package com.cognora.platform.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.tenant.domain.entity.TenantFeature;
import com.cognora.platform.tenant.service.ITenantFeatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/tenant-feature")
@Tag(name = "租户功能", description = "提供租户功能开关的维护、状态校验与分页查询接口。")
public class TenantFeatureController {
    private final ITenantFeatureService tenantFeatureService;

    @Operation(summary = "创建租户功能", description = "为指定租户新增一个功能开关记录并返回创建结果。")
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:feature:create')")
    @AuditLog(operation = "创建租户功能", businessType = "TENANT", riskLevel = 3)
    public TenantFeature create(@RequestBody TenantFeature entity) {
        log.info("[API] Create tenant feature: tenantId={}, featureCode={}", (entity.getTenantId() + "").replace("\r", "_").replace("\n", "_"), (entity.getFeatureCode() + "").replace("\r", "_").replace("\n", "_"));
        return tenantFeatureService.createFeature(entity);
    }

    @Operation(summary = "查询功能详情", description = "根据功能记录 ID 查询租户功能开关详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('tenant:feature:page', 'tenant:feature:view')")
