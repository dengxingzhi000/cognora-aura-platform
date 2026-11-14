package com.cognora.lms.logistics.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.lms.logistics.domain.entity.TmsRoute;
import com.cognora.lms.logistics.service.ITmsRouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/v1/tms-route")
@Tag(name = "运输路线", description = "配送运输路线档案的维护与按骑手、日期查询接口。")
public class TmsRouteController {

    private final ITmsRouteService routeService;

    @Operation(summary = "查询路线详情", description = "根据路线主键 ID 获取运输路线详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('logistics:route:page', 'logistics:route:view')")
