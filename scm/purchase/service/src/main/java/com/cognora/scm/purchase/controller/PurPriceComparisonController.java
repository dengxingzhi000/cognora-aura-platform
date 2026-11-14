package com.cognora.scm.purchase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.scm.purchase.domain.entity.PurPriceComparison;
import com.cognora.scm.purchase.service.IPurPriceComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/pur-price-comparison")
@Tag(name = "比价管理", description = "供应商比价单的查询、维护与审批管理。")
public class PurPriceComparisonController {

    private final IPurPriceComparisonService purPriceComparisonService;

    @Operation(summary = "根据 ID 查询比价单", description = "按主键查询比价单的详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('purchase:comparison:page', 'purchase:comparison:view')")
