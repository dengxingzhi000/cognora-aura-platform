package com.cognora.scm.purchase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.scm.purchase.domain.entity.PurContract;
import com.cognora.scm.purchase.service.IPurContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/pur-contract")
@Tag(name = "采购合同", description = "采购合同的查询、维护、签订与终止管理。")
public class PurContractController {

    private final IPurContractService purContractService;

    @Operation(summary = "根据 ID 查询合同", description = "按主键查询采购合同的详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('purchase:contract:list-by-status', 'purchase:contract:view')")
