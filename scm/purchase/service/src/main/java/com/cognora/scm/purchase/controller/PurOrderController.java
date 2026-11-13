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
    @PreAuthorize("hasAuthority('purchase:order:page')")
    public ApiResponse<Page<PurOrder>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "采购单号或供应商名称关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "采购订单状态") @RequestParam(required = false) Integer status,
            @Parameter(description = "供应商 ID") @RequestParam(required = false) String supplierId) {
        return ApiResponse.success(purOrderQueryService.pageList(page, size, keyword, status, supplierId));
    }

    @Operation(summary = "查询采购订单详情", description = "按主键查询采购订单，不存在或已删除时返回 404。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:order:view')")
    public ApiResponse<PurOrder> getById(@Parameter(description = "采购订单 ID") @PathVariable String id) {
        PurOrder order = purOrderQueryService.getById(id);
        if (order == null || Boolean.TRUE.equals(order.getDeleted())) {
            return ApiResponse.fail(404, "采购订单不存在");
        }
        return ApiResponse.success(order);
    }

    @Operation(summary = "查询采购订单明细", description = "查询指定采购订单下的全部明细行。")
    @GetMapping("/{id}/items")
    @PreAuthorize("hasAuthority('purchase:order:list-items')")
    public ApiResponse<List<PurOrderItem>> listItems(@Parameter(description = "采购订单 ID") @PathVariable String id) {
        return ApiResponse.success(purOrderItemService.listByOrderId(id));
    }

    /**
     * 采购在途量（服务间只读）。
     *
     * <p>platform-inventory 的补货计算用它做「在途去重」：available + inTransit
     * 才决定是否触发补货，漏掉这一项会让同一批货在到货前被反复建议。
     *
     * <p>路径固定为 {@code /api/v1/pur-order/in-transit}，由
     * {@code cognora.security.api-filter.internal-signature-paths} 要求服务间 HMAC 签名，
     * 不登记用户权限码（它是内部通道，不是管理端功能）。
     */
    @Operation(summary = "采购在途量",
            description = "按 SKU×仓 汇总已下单未收完的数量，供库存补货的在途去重使用。服务间内部端点。")
    @GetMapping("/in-transit")
    public ApiResponse<List<PurchaseInTransitRow>> inTransit(
            @Parameter(description = "仓库 ID；为空表示全部仓库") @RequestParam(required = false) String warehouseId) {
        return ApiResponse.success(purOrderQueryService.sumUnreceivedInTransit(warehouseId));
    }
}
