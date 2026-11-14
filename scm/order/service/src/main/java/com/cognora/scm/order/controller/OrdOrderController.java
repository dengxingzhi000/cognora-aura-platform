package com.cognora.scm.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.scm.order.dto.OrderCancelRequest;
import com.cognora.scm.order.dto.OrderCreateRequest;
import com.cognora.scm.order.dto.OrderDashboardCompare;
import com.cognora.scm.order.dto.OrderDashboardRange;
import com.cognora.scm.order.dto.OrderDashboardSummaryResponse;
import com.cognora.scm.order.dto.OrderItemResponse;
import com.cognora.scm.order.dto.OrderQueryRequest;
import com.cognora.scm.order.dto.OrderResponse;
import com.cognora.scm.order.dto.OrderStatusUpdateRequest;
import com.cognora.scm.order.service.command.OrdOrderCommandService;
import com.cognora.scm.order.service.query.OrdOrderDashboardQueryService;
import com.cognora.scm.order.service.query.OrdOrderQueryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 订单管理控制器。
 *
 * <p>按 CQRS 拆分职责：</p>
 * <ul>
 *   <li>读操作 → {@link OrdOrderQueryService}（{@code @Slave}，启用读写分离后路由到只读副本）</li>
 *   <li>写操作 → {@link OrdOrderCommandService}（{@code @Master} + 事务 + 状态机校验 + 事件溯源）</li>
 * </ul>
 *
 * <p>命令接口直接返回写后实体，不再「写完立刻回查」：一旦 {@code @Slave} 真正路由到
 * 只读副本，回查会因复制延迟读到旧状态。</p>
 *
 * <p>所有响应统一包装为 {@link ApiResponse}；领域实体不在响应中直接暴露。</p>
 *
 * <p>相比旧版本，删除/替换的不安全端点：</p>
 * <ul>
 *   <li>删除 {@code GET /} 无分页全表查询（生产危险）</li>
 *   <li>删除 {@code PUT /} 任意字段更新（绕过领域校验/状态机）</li>
 *   <li>删除 {@code DELETE /{id}} 物理删除（改用 {@code PUT /{id}/cancel} 走领域取消）</li>
 *   <li>{@code GET /page} 和 {@code GET /user/{userId}/page} 合并为 {@code POST /query} 统一条件查询</li>
 * </ul>
 *
 * @author SCM Platform Team
 */
@Validated
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "订单管理", description = "订单的创建、查询、状态流转与取消接口。")
public class OrdOrderController {
    private final OrdOrderQueryService queryService;
    private final OrdOrderCommandService commandService;
    private final OrdOrderDashboardQueryService dashboardQueryService;

    // ───────────────────────── Query Side ─────────────────────────

    /**
     * 按 ID 查询订单。
     */
    @Operation(summary = "查询订单", description = "根据订单 ID 查询订单详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('order:order:view', 'order-center:order:get-order', 'order-center:order:page')")
}