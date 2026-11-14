package com.cognora.platform.inventory.service.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.inventory.domain.entity.Inventory;
import com.cognora.platform.inventory.dto.InventoryValuationQueryRequest;
import com.cognora.platform.inventory.dto.InventoryValuationRow;
import com.cognora.platform.inventory.dto.InventoryValuationStats;
import com.cognora.platform.inventory.mapper.InvInventoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 库存估值报表服务（只读）。
 *
 * <p>估值统一使用台账的移动加权均价 × 总库存，与 {@code InventoryStatsResponse}
 * 的口径一致；这里刻意不再写第二套成本逻辑，避免报表与总览对不上。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Service
@RequiredArgsConstructor
public class InventoryValuationQueryService {

  private final InvInventoryMapper inventoryMapper;

  @Slave
  public Page<InventoryValuationRow> page(InventoryValuationQueryRequest request) {
    Page<Inventory> page = inventoryMapper.selectPage(
        new Page<>(request.getPage(), request.getSize()), buildWrapper(request));
    Page<InventoryValuationRow> responsePage =
        new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
    responsePage.setRecords(page.getRecords().stream()
        .map(inventory -> toRow(inventory, request.getAgingDays()))
        .toList());
    return responsePage;
  }

  /** 导出用：按同一筛选条件取出全部行（上限由 size 之外的调用方控制）。 */
  @Slave
  public List<InventoryValuationRow> list(InventoryValuationQueryRequest request) {
    return inventoryMapper.selectList(buildWrapper(request)).stream()
        .map(inventory -> toRow(inventory, request.getAgingDays()))
        .toList();
  }

  @Slave
  public InventoryValuationStats stats(InventoryValuationQueryRequest request) {
    List<InventoryValuationRow> rows = list(request);

    long totalQty = 0;
    long availableQty = 0;
    long lockedQty = 0;
    long damagedQty = 0;
    long slowMoving = 0;
    BigDecimal totalValue = BigDecimal.ZERO;
    BigDecimal slowMovingValue = BigDecimal.ZERO;

    for (InventoryValuationRow row : rows) {
      totalQty += row.getTotalStock() == null ? 0 : row.getTotalStock();
      availableQty += row.getAvailableStock() == null ? 0 : row.getAvailableStock();
      lockedQty += row.getLockedStock() == null ? 0 : row.getLockedStock();
      damagedQty += row.getDamagedStock() == null ? 0 : row.getDamagedStock();
      BigDecimal value = row.getStockValue() == null ? BigDecimal.ZERO : row.getStockValue();
      totalValue = totalValue.add(value);
      if (request.getAgingDays() != null) {
        Long aging = row.getAgingDays();
        if (aging != null && aging >= request.getAgingDays()) {
          slowMoving++;
          slowMovingValue = slowMovingValue.add(value);
        }
      }
    }

    InventoryValuationStats stats = new InventoryValuationStats();
    stats.setRowCount((long) rows.size());
    stats.setTotalStockQuantity(totalQty);
    stats.setAvailableStockQuantity(availableQty);
    stats.setLockedStockQuantity(lockedQty);
    stats.setDamagedStockQuantity(damagedQty);
    stats.setTotalStockValue(totalValue);
    stats.setSlowMovingCount(request.getAgingDays() == null ? null : slowMoving);
    stats.setSlowMovingValue(request.getAgingDays() == null ? null : slowMovingValue);
    return stats;
  }

  private LambdaQueryWrapper<Inventory> buildWrapper(InventoryValuationQueryRequest request) {
    LambdaQueryWrapper<Inventory> wrapper = Wrappers.lambdaQuery();
    wrapper.eq(StringUtils.hasText(request.getSkuId()), Inventory::getSkuId, request.getSkuId())
        .eq(StringUtils.hasText(request.getWarehouseId()),
            Inventory::getWarehouseId, request.getWarehouseId())
        .eq(StringUtils.hasText(request.getLocationCode()),
            Inventory::getLocationCode, request.getLocationCode());

    if (StringUtils.hasText(request.getStockStatus())) {
      switch (request.getStockStatus()) {
        case "OUT_OF_STOCK" -> wrapper.eq(Inventory::getAvailableStock, 0);
        case "LOW_STOCK" ->
            wrapper.apply("available_stock > 0 AND available_stock <= safety_stock");
        case "NORMAL" -> wrapper.apply("available_stock > safety_stock");
        default -> {
          // 未知状态不静默忽略，交由调用方看到空结果而非错误数据
        }
      }
    }
    wrapper.eq(Inventory::getDeleted, false);
    wrapper.orderByDesc(Inventory::getTotalStock);
    return wrapper;
  }

  private static InventoryValuationRow toRow(Inventory inventory, Integer agingDays) {
    InventoryValuationRow row = new InventoryValuationRow();
    row.setSkuId(inventory.getSkuId());
    row.setWarehouseId(inventory.getWarehouseId());
    row.setLocationCode(inventory.getLocationCode());
    row.setTotalStock(inventory.getTotalStock());
    row.setAvailableStock(inventory.getAvailableStock());
    row.setLockedStock(inventory.getLockedStock());
    row.setDamagedStock(inventory.getDamagedStock());
    row.setSafetyStock(inventory.getSafetyStock());
    row.setAverageCost(inventory.getAverageCost());

    int total = inventory.getTotalStock() == null ? 0 : inventory.getTotalStock();
    BigDecimal cost = inventory.getAverageCost() == null
        ? BigDecimal.ZERO : inventory.getAverageCost();
    row.setStockValue(cost.multiply(BigDecimal.valueOf(total)));

    if (agingDays != null && inventory.getLastInboundAt() != null) {
      row.setAgingDays(ChronoUnit.DAYS.between(
          inventory.getLastInboundAt(), LocalDateTime.now()));
    }

    int available = inventory.getAvailableStock() == null ? 0 : inventory.getAvailableStock();
    int safety = inventory.getSafetyStock() == null ? 0 : inventory.getSafetyStock();
    if (available == 0) {
      row.setStockStatus("OUT_OF_STOCK");
    } else if (available <= safety) {
      row.setStockStatus("LOW_STOCK");
    } else {
      row.setStockStatus("NORMAL");
    }
    return row;
  }
}
