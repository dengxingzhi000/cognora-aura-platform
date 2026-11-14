package com.cognora.platform.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.tenant.domain.entity.TenantSubscription;
import com.cognora.platform.tenant.service.ITenantSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/tenant-subscription")
@Tag(name = "租户订阅", description = "提供租户套餐订阅的创建、开通、取消与分页查询接口。")
public class TenantSubscriptionController {
    private final ITenantSubscriptionService tenantSubscriptionService;

    @Operation(summary = "创建租户订阅", description = "新增一条租户套餐订阅记录并返回创建结果。")
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:subscription:create')")
    @AuditLog(operation = "创建租户订阅", businessType = "TENANT", riskLevel = 3)
    public TenantSubscription create(@RequestBody TenantSubscription entity) {
        log.info("[API] 创建租户订阅: tenantId={}, packageId={}", (entity.getTenantId() + "").replace("\r", "_").replace("\n", "_"), (entity.getPackageId() + "").replace("\r", "_").replace("\n", "_"));
        return tenantSubscriptionService.createSubscription(entity);
    }

    @Operation(summary = "查询订阅详情", description = "根据订阅 ID 查询租户订阅详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('tenant:subscription:page', 'tenant:subscription:view')")
