package com.cognora.scm.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cognora.scm.purchase.domain.dto.PurchaseInTransitRow;
import com.cognora.scm.purchase.domain.entity.PurOrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 閲囪喘璁㈠崟鏄庣粏锟組apper 鎺ュ彛
 * </p>
 *
 * @author deng
 * @since 2025-12-25
 */
public interface PurOrderItemMapper extends BaseMapper<PurOrderItem> {

    /**
     * 按 SKU×仓 汇总「已下单未收完」的数量，供库存补货的在途去重使用。
     *
     * <p>口径：
     * <ul>
     *   <li>订单状态取 2/3/4（已审批 / 已下单 / 部分收货）：草稿与待审批还没形成
     *       对供应商的承诺，算进在途会让补货被虚假满足；已完成(5)与已取消(6)
     *       的货不会再来，异常(7)交给人工，均不计入；</li>
     *   <li>只统计 {@code sku_id} 非空的明细：material 口径的行无法映射回 SKU，
     *       强行计入会污染去重；</li>
     *   <li>{@code received_quantity} 可为 NULL（从未收货），用 COALESCE 归零；</li>
     *   <li>差额为负（超收）时按 0 计，不冲抵其它行。</li>
     * </ul>
     * 租户条件：{@code TenantInterceptor} 只给 JOIN 的驱动表追加租户谓词
     * （AGENTS 记录的已知限制），因此这里显式补 {@code o.tenant_id = i.tenant_id}，
     * 保证两张表都被限定在同一租户内，而不是依赖驱动表的唯一约束。
     */
    @Select("SELECT i.sku_id AS \"skuId\", o.warehouse_id AS \"warehouseId\", "
            + "COALESCE(SUM(GREATEST(i.quantity - COALESCE(i.received_quantity, 0), 0)), 0) "
            + "AS \"inTransitQty\" "
            + "FROM pur_order_item i "
            + "JOIN pur_order o ON o.id = i.order_id AND o.deleted = false "
            + "AND o.tenant_id = i.tenant_id "
            + "WHERE i.sku_id IS NOT NULL "
            + "AND o.status IN (2, 3, 4) "
            + "AND (#{warehouseId} IS NULL OR o.warehouse_id = CAST(#{warehouseId} AS UUID)) "
            + "GROUP BY i.sku_id, o.warehouse_id")
    List<PurchaseInTransitRow> sumUnreceivedBySkuAndWarehouse(
            @Param("warehouseId") String warehouseId);
}

