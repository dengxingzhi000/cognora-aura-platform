package com.cognora.scm.purchase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.scm.purchase.domain.entity.PurReceipt;
import com.cognora.scm.purchase.service.IPurReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/pur-receipt")
@Tag(name = "采购收货", description = "采购收货单的查询、收货、质检与上架接口")
public class PurReceiptController {

    private final IPurReceiptService purReceiptService;

    @Operation(summary = "根据 ID 查询收货单", description = "按主键查询单张采购收货单的完整信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('purchase:receipt:page', 'purchase:receipt:view')")
