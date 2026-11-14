package com.cognora.scm.purchase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.scm.purchase.domain.dto.PurchaseInTransitRow;
import com.cognora.scm.purchase.domain.entity.PurOrder;
import com.cognora.scm.purchase.domain.entity.PurOrderItem;
import com.cognora.scm.purchase.service.IPurOrderItemService;
import com.cognora.scm.purchase.service.query.PurOrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 采购订单读接口。列表页与详情页使用，写入由采购流程负责。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/pur-order")
@Tag(name = "采购订单", description = "采购订单的列表、详情与明细查询。")
public class PurOrderController {

    private final PurOrderQueryService purOrderQueryService;
    private final IPurOrderItemService purOrderItemService;

    @Operation(summary = "分页查询采购订单", description = "按单号/供应商关键字、状态与供应商分页查询采购订单。")
    @GetMapping("/page")
    @PreAuthorize("hasAnyAuthority('purchase:order:view', 'purchase:order:page')")
