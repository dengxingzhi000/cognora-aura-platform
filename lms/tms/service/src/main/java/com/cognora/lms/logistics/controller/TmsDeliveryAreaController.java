package com.cognora.lms.logistics.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.lms.logistics.domain.entity.TmsDeliveryArea;
import com.cognora.lms.logistics.service.ITmsDeliveryAreaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/v1/tms-delivery-area")
@Tag(name = "配送区域", description = "承运商配送范围区域的维护与覆盖范围校验接口。")
public class TmsDeliveryAreaController {

    private final ITmsDeliveryAreaService deliveryAreaService;

    @Operation(summary = "查询配送区域详情", description = "根据配送区域主键 ID 获取区域详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('logistics:area:check-coverage', 'logistics:area:view')")
