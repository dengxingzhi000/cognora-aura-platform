package com.cognora.scm.purchase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.scm.purchase.domain.entity.PurPlan;
import com.cognora.scm.purchase.service.IPurPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/pur-plan")
@Tag(name = "采购计划", description = "采购计划的查询、维护、提交、审批与完结管理。")
public class PurPlanController {

    private final IPurPlanService purPlanService;

    @Operation(summary = "根据 ID 查询计划", description = "按主键查询采购计划的详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('purchase:plan:list-by-status', 'purchase:plan:view')")
