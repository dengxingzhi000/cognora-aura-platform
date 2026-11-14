package com.cognora.platform.inventory.service.query;

import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.inventory.domain.entity.InvDemandForecast;
import com.cognora.platform.inventory.dto.DemandDailyAggregate;
import com.cognora.platform.inventory.mapper.InvDemandAggregationMapper;
import com.cognora.platform.inventory.mapper.InvDemandForecastMapper;
import com.cognora.platform.inventory.service.replenishment.WeightedMovingAverage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 需求聚合查询（只读）。
 *
 * <p>从 {@code inv_log} 按 SKU×仓×天 聚合<b>销售出库</b>，再用
 * {@link WeightedMovingAverage} 算出日需求 μ。出库口径见
 * {@code InvDemandAggregationMapper}：调拨/报损/盘点调整不计入需求。
 *
 * <p>本服务只读不写；预测结果由 {@code ReplenishmentComputeService} 在同一事务里落库。
 *
 * <p>注意：本服务<b>不</b>参与库存热点路径（AGENTS：热点只 Redis+Lua）。补货计算由
 * XXL-Job 或人工触发，扫的是历史流水，不在下单链路上。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandAggregationQueryService {

  private final InvDemandAggregationMapper aggregationMapper;
  private final InvDemandForecastMapper forecastMapper;

  /**
   * 计算租户内全部 SKU×仓 的日需求。
   *
   * @param lookbackDays 回看天数（&gt;0）
   * @return key 为 {@code skuId|warehouseId} 的预测结果
   */
  @Slave
  public Map<String, SkuDemand> aggregateAll(int lookbackDays) {
    UUID tenantId = TenantContextHolder.getRequiredTenantId();
    LocalDate today = LocalDate.now();
    LocalDate startDate = today.minusDays(Math.max(1, lookbackDays) - 1L);
    LocalDateTime start = startDate.atStartOfDay();
    LocalDateTime end = today.plusDays(1).atStartOfDay();

    List<DemandDailyAggregate> rows =
        aggregationMapper.selectDailySalesOutbound(tenantId.toString(), start, end);
    return buildDemand(rows, startDate, today);
  }

  /**
   * 计算单个 SKU×仓 的日需求，供手动触发时只重算关心的 SKU。
   */
  @Slave
  public SkuDemand aggregateOne(String skuId, String warehouseId, int lookbackDays) {
    UUID tenantId = TenantContextHolder.getRequiredTenantId();
    LocalDate today = LocalDate.now();
    LocalDate startDate = today.minusDays(Math.max(1, lookbackDays) - 1L);
    LocalDateTime start = startDate.atStartOfDay();
    LocalDateTime end = today.plusDays(1).atStartOfDay();

    List<DemandDailyAggregate> rows = aggregationMapper.selectDailySalesOutboundForSku(
        tenantId.toString(), skuId, warehouseId, start, end);
    Map<String, SkuDemand> demand = buildDemand(rows, startDate, today);
    return demand.get(key(skuId, warehouseId));
  }

  /**
   * 读取已落库的最新预测（解释用，不重算）。
   */
  @Slave
  public InvDemandForecast latestForecast(String skuId, String warehouseId) {
    return forecastMapper.selectOne(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.<InvDemandForecast>lambdaQuery()
            .eq(InvDemandForecast::getSkuId, skuId)
            .eq(InvDemandForecast::getWarehouseId, warehouseId)
            .orderByDesc(InvDemandForecast::getForecastDate)
            .orderByDesc(InvDemandForecast::getVersion)
            .last("LIMIT 1"));
  }

  // ---------------------------------------------------------------- helpers

  /** 把 SQL 的稀疏日聚合整理成每 SKU×仓 一条连续序列后再算 μ。 */
  private Map<String, SkuDemand> buildDemand(List<DemandDailyAggregate> rows,
                                             LocalDate startDate,
                                             LocalDate endDate) {
    Map<String, Map<LocalDate, BigDecimal>> series = new LinkedHashMap<>();
    // 没有出库记录的 SKU 不会出现在 SQL 结果里；这里只处理出现过的组合，
    // 全无出库的 SKU 由计算侧按「无需求」处理（不会触发补货）。
    Set<String> touched = new LinkedHashSet<>();
    if (rows != null) {
      for (DemandDailyAggregate row : rows) {
        if (row == null || row.getSkuId() == null || row.getWarehouseId() == null) {
          continue;
        }
        String k = key(row.getSkuId(), row.getWarehouseId());
        touched.add(k);
        series.computeIfAbsent(k, x -> new LinkedHashMap<>())
            .merge(row.getDemandDate(),
                row.getDemandQuantity() == null ? BigDecimal.ZERO : row.getDemandQuantity(),
                BigDecimal::add);
      }
    }

    Map<String, SkuDemand> result = new LinkedHashMap<>();
    for (String k : touched) {
      List<BigDecimal> daily = WeightedMovingAverage.densify(
          series.get(k), startDate, endDate);
      WeightedMovingAverage.Result ma = WeightedMovingAverage.calculate(
          daily, WeightedMovingAverage.DEFAULT_MIN_SAMPLE_DAYS);
      result.put(k, new SkuDemand(k, ma.dailyDemand(), ma.sampleDays(),
          ma.lookbackDays(), ma.lowConfidence()));
    }
    return result;
  }

  private static String key(String skuId, String warehouseId) {
    return skuId + "|" + warehouseId;
  }

  /**
   * 单个 SKU×仓 的需求预测。
   *
   * @param key            {@code skuId|warehouseId}
   * @param dailyDemand    加权移动平均日需求 μ
   * @param sampleDays     有出库的天数
   * @param lookbackDays   回看总天数
   * @param lowConfidence  样本不足（冷启动）
   */
  public record SkuDemand(String key, BigDecimal dailyDemand, int sampleDays,
                          int lookbackDays, boolean lowConfidence) {
  }
}
