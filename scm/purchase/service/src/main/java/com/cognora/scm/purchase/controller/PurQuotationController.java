package com.cognora.scm.purchase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.scm.purchase.domain.entity.PurQuotation;
import com.cognora.scm.purchase.service.IPurQuotationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/pur-quotation")
@Tag(name = "供应商报价", description = "供应商报价单的查询、维护、提交与选用管理。")
public class PurQuotationController {

    private final IPurQuotationService purQuotationService;

    @Operation(summary = "根据 ID 查询报价单", description = "按主键查询供应商报价单的详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('purchase:quotation:page', 'purchase:quotation:view')")
