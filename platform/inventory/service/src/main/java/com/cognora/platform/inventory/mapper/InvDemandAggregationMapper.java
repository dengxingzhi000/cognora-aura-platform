package com.cognora.platform.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cognora.platform.inventory.domain.entity.InvLog;
import com.cognora.platform.inventory.dto.DemandDailyAggregate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求聚合用的流水查询。
 *
 * <p><b>为什么单独写 SQL 而不是用 BaseMapper + 内存聚合：</b>一个租户的历史流水可以是
 * 千万级，按 SKU×天 拉回 JVM 聚合会在补货 Job 里制造 O(N) 堆占用。这里让 PostgreSQL
 * 直接按天分组，只回传「每 SKU 每天一行」。
 *
 * <p><b>口径（关键）：</b>需求预测只认<b>销售出库</b>。{@code inv_log} 里
 * {@code operation_type=2}（出库）同时被调拨、报损、盘点调整复用，只用 operation_type
 * 会把「仓库之间搬货」和「货坏了」当成需求，把预测系统性抬高。因此这里按
 * {@code business_type} 白名单过滤：
 * <ul>
 *   <li>{@code OUTBOUND}  手工销售出库（adjustType=2）</li>
 *   <li>{@code CONFIRM}   预占确认实扣，即订单真正发货</li>
 * </ul>
 * 排除 {@code TRANSFER_OUT}（调拨）、{@code ADJUST}（报损/盘点）、{@code TCC_*}/{@code DUBBO_DEDUCT}
 * （内部账本动作，可能重复计数）。白名单而非黑名单：新增的出库业务类型默认<b>不计入</b>
 * 需求，宁可少算也不虚高——少算由人审兜底，虚高会直接变成过量采购。
 *
 * <p>{@code quantity} 出库为负，聚合时取 {@code -quantity} 转为正数需求量。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Mapper
public interface InvDemandAggregationMapper extends BaseMapper<InvLog> {

  /** 计入需求的出库业务类型（销售口径）。 */
  String SALES_OUTBOUND_TYPES = "'OUTBOUND','CONFIRM'";

  /**
   * 按 SKU×仓×天 聚合销售出库量。
   *
   * <p>租户条件由 {@code TenantInterceptor} 追加；这里显式再写一次 tenant_id 是为了让
   * 该语句在没有租户上下文（Job 循环外误调用）时也不会跨租户返数。
   *
   * @param tenantId 租户 ID 字符串（UUID）
   * @param start    起始时间（含）
   * @param end      结束时间（不含）
   * @return 每 SKU×仓×天 一行，按日期升序
   */
  @Select("SELECT sku_id AS skuId, warehouse_id AS warehouseId, "
      + "DATE(create_time) AS demandDate, "
      + "COALESCE(SUM(-quantity), 0) AS demandQuantity "
      + "FROM inv_log "
      + "WHERE tenant_id = CAST(#{tenantId} AS UUID) "
      // inv_log 是 append-only 流水表，没有 deleted 列（不像业务表那样软删）。
      // 早期版本这里写了 "AND deleted = false"，在真实库上直接报
      // column "deleted" does not exist —— 需求聚合因此从未跑通过。
      + "AND operation_type = 2 "
      + "AND business_type IN (" + SALES_OUTBOUND_TYPES + ") "
      + "AND quantity < 0 "
      + "AND create_time >= #{start} AND create_time < #{end} "
      + "GROUP BY sku_id, warehouse_id, DATE(create_time) "
      + "ORDER BY DATE(create_time) ASC")
  List<DemandDailyAggregate> selectDailySalesOutbound(@Param("tenantId") String tenantId,
                                                      @Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end);

  /**
   * 指定 SKU×仓 的日聚合，供单点重算（手动触发指定 SKU）。
   */
  @Select("SELECT sku_id AS skuId, warehouse_id AS warehouseId, "
      + "DATE(create_time) AS demandDate, "
      + "COALESCE(SUM(-quantity), 0) AS demandQuantity "
      + "FROM inv_log "
      + "WHERE tenant_id = CAST(#{tenantId} AS UUID) "
      + "AND operation_type = 2 "
      + "AND business_type IN (" + SALES_OUTBOUND_TYPES + ") "
      + "AND quantity < 0 "
      + "AND sku_id = CAST(#{skuId} AS UUID) "
      + "AND warehouse_id = CAST(#{warehouseId} AS UUID) "
      + "AND create_time >= #{start} AND create_time < #{end} "
      + "GROUP BY sku_id, warehouse_id, DATE(create_time) "
      + "ORDER BY DATE(create_time) ASC")
  List<DemandDailyAggregate> selectDailySalesOutboundForSku(
      @Param("tenantId") String tenantId,
      @Param("skuId") String skuId,
      @Param("warehouseId") String warehouseId,
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end);
}
