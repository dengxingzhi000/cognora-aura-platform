package com.cognora.lms.logistics.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.lms.logistics.support.webhook.WebhookSignatureVerifier;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.lms.logistics.domain.entity.TmsTracking;
import com.cognora.lms.logistics.service.ITmsTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/v1/tms-tracking")
@Tag(name = "物流跟踪", description = "运单物流轨迹的查询与轨迹节点上报接口。")
public class TmsTrackingController {

    private final ITmsTrackingService trackingService;
    private final WebhookSignatureVerifier webhookSignatureVerifier;
    private final ObjectMapper objectMapper;

    @Operation(summary = "查询轨迹详情", description = "根据轨迹主键 ID 获取单条物流轨迹记录。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('logistics:tracking:page', 'logistics:tracking:view')")
