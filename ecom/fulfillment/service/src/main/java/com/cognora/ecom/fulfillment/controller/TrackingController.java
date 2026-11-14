package com.cognora.ecom.fulfillment.controller;

import com.cognora.ecom.fulfillment.domain.entity.Tracking;
import com.cognora.ecom.fulfillment.service.ITrackingService;
import com.cognora.platform.common.log.annotation.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/v1/fulfillments")
@Tag(name = "履约跟踪", description = "履约轨迹事件的登记与按物流单号、履约单号查询跟踪记录的接口。")
public class TrackingController {

    private final ITrackingService trackingService;

    @Operation(summary = "记录跟踪事件", description = "登记一条履约轨迹事件，用于形成物流跟踪记录。")
    @PostMapping("/tracking")
    @PreAuthorize("hasAuthority('fulfillment:tracking:record-event')")
    @AuditLog(operation = "登记履约跟踪事件", businessType = "FULFILLMENT", riskLevel = 2)
    public Tracking recordEvent(@Valid @RequestBody Tracking tracking) {
        log.info("[API] Record tracking event: fulfillmentNo={}, status={}",
                (tracking.getFulfillmentNo() + "").replace("\r", "_").replace("\n", "_"), (tracking.getStatus() + "").replace("\r", "_").replace("\n", "_"));
        return trackingService.recordEvent(tracking);
    }

    @Operation(summary = "按物流单号查询跟踪", description = "根据物流运单号查询其下的全部履约轨迹记录。")
    @GetMapping("/tracking/{trackingNo}")
    @PreAuthorize("hasAnyAuthority('fulfillment:tracking:get-by-fulfillment-no', 'fulfillment:tracking:get-by-tracking-no')")
