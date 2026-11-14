package com.cognora.scm.purchase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.scm.purchase.domain.entity.PurRfq;
import com.cognora.scm.purchase.service.IPurRfqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/pur-rfq")
@Tag(name = "询价管理", description = "采购询价单的查询、发布与关闭接口")
public class PurRfqController {

    private final IPurRfqService purRfqService;

    @Operation(summary = "根据 ID 查询询价单", description = "按主键查询单张采购询价单的完整信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('purchase:rfq:list-by-status', 'purchase:rfq:view')")
