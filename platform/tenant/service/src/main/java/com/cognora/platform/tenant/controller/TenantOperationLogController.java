package com.cognora.platform.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.tenant.domain.entity.TenantOperationLog;
import com.cognora.platform.tenant.service.ITenantOperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/tenant-operation-log")
@Tag(name = "租户操作日志", description = "提供租户操作日志的写入、查询、删除与分页检索接口。")
public class TenantOperationLogController {
    private final ITenantOperationLogService tenantOperationLogService;

    @Operation(summary = "创建操作日志", description = "写入一条租户操作日志记录并返回创建结果。")
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:operation-log:create')")
    @AuditLog(operation = "登记租户操作日志", businessType = "TENANT", riskLevel = 2)
    public TenantOperationLog create(@RequestBody TenantOperationLog entity) {
        log.info("[API] 创建租户操作日志: tenantId={}, operationType={}", (entity.getTenantId() + "").replace("\r", "_").replace("\n", "_"), (entity.getOperationType() + "").replace("\r", "_").replace("\n", "_"));
        return tenantOperationLogService.createLog(entity);
    }

    @Operation(summary = "查询日志详情", description = "根据日志 ID 查询租户操作日志详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('tenant:operation-log:page', 'tenant:operation-log:view')")
