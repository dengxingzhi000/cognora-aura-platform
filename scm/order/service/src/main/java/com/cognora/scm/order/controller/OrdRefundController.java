package com.cognora.scm.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.scm.order.dto.RefundApproveRequest;
import com.cognora.scm.order.dto.RefundCreateRequest;
import com.cognora.scm.order.dto.RefundItemResponse;
import com.cognora.scm.order.dto.RefundQueryRequest;
import com.cognora.scm.order.dto.RefundRejectRequest;
import com.cognora.scm.order.dto.RefundResponse;
import com.cognora.scm.order.service.command.OrdRefundCommandService;
import com.cognora.scm.order.service.query.OrdRefundItemQueryService;
import com.cognora.scm.order.service.query.OrdRefundQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 退款 controller。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>GET {@code /{id}} — 按 ID 查询退款单</li>
 *   <li>GET {@code /{id}/items} — 查询退款明细</li>
 *   <li>GET {@code /order/{orderId}} — 按订单查退款列表</li>
 *   <li>POST {@code /query} — 统一条件分页查询</li>
 *   <li>POST {@code /} — 创建退款单（带 items）</li>
 *   <li>PUT {@code /{id}/approve} — 审核通过</li>
 *   <li>PUT {@code /{id}/reject} — 拒绝退款</li>
 *   <li>PUT {@code /{id}/complete} — 完成退款（线下已退款后回调）</li>
 * </ul>
 *
 * <p><b>不</b>暴露任意字段更新 / 物理删除入口。</p>
 *
 * @author SCM Platform Team
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/refunds")
@RequiredArgsConstructor
@Tag(name = "订单退款", description = "退款单的查询、创建、审核与完成接口。")
public class OrdRefundController {

    private final OrdRefundQueryService queryService;
    private final OrdRefundItemQueryService refundItemQueryService;
    private final OrdRefundCommandService commandService;

    @Operation(summary = "查询退款单", description = "根据退款单 ID 查询退款详情及其明细。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order:refund:view')")
    public ApiResponse<RefundResponse> getById(@Parameter(description = "退款单 ID") @PathVariable UUID id) {
        log.info("[API] Query refund: id={}", id);
        return ApiResponse.success(queryService.getResponseById(id));
    }

    @Operation(summary = "查询退款明细", description = "根据退款单 ID 查询退款明细列表。")
    @GetMapping("/{id}/items")
    @PreAuthorize("hasAnyAuthority('order:refund:list-by-order-id', 'order:refund:list-items')")
}