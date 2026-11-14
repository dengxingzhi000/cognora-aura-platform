package com.cognora.ecom.fulfillment.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.ecom.fulfillment.domain.entity.FulfillmentOrder;
import com.cognora.ecom.fulfillment.service.IFulfillmentOrderService;
import com.cognora.platform.common.log.annotation.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/fulfillments")
@Tag(name = "履约管理", description = "履约单的创建、分页查询以及取消、拣货、打包、发货、签收等状态流转接口。")
public class FulfillmentController {

    private final IFulfillmentOrderService fulfillmentService;

    @Operation(summary = "分页查询履约单", description = "按订单号、用户与状态分页查询履约单。")
    @GetMapping("/page")
    @PreAuthorize("hasAnyAuthority('fulfillment:fulfillment:get-fulfillment', 'fulfillment:fulfillment:page')")
