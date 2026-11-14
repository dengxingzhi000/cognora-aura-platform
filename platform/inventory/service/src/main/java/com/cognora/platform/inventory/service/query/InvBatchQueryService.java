package com.cognora.platform.inventory.service.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.inventory.domain.entity.InvBatch;
import com.cognora.platform.inventory.dto.InvBatchQueryRequest;
import com.cognora.platform.inventory.dto.InvBatchResponse;
import com.cognora.platform.inventory.dto.InvBatchStatsResponse;
import com.cognora.platform.inventory.mapper.InvBatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 批次/效期报表查询服务。
 *
 * <p>只读：不修改批次任何状态。临期判定统一以 {@code expiry_date} 为准，
 * 不使用可能过期的 {@code status} 列（该列由写入路径维护，历史数据可能滞后），
 * 因此“已过期/临期”的计数在查询时按当天日期重算，避免报表与事实漂移。
 *
 * <p>租户隔离由 {@code TenantInterceptor} 在 SQL 层追加 tenant_id。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Service
@RequiredArgsConstructor
public class InvBatchQueryService {

  private final InvBatchMapper invBatchMapper;

  @Slave
  public Page<InvBatchResponse> query(InvBatchQueryRequest request) {
    LocalDate today = LocalDate.now();
    Page<InvBatch> page = invBatchMapper.selectPage(
        new Page<>(request.getPage(), request.getSize()), buildWrapper(request, today));

    Page<InvBatchResponse> responsePage =
        new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
    responsePage.setRecords(page.getRecords().stream()
        .map(batch -> InvBatchResponse.from(batch, today))
        .toList());
    return responsePage;
  }

  /**
   * 汇总同一筛选条件下的批次规模。
   *
   * <p>为避免把“临期窗口”与服务层的 now() 口径分叉，这里复用同一条
   * wrapper 并拉取匹配行在内存聚合：批次表是运营报表数据，行数量级远小于
   * 台账，且本接口不进入下单热路径，因此可接受。
   */
  @Slave
  public InvBatchStatsResponse stats(InvBatchQueryRequest request) {
    LocalDate today = LocalDate.now();
    List<InvBatch> batches = invBatchMapper.selectList(buildWrapper(request, today));

    long expired = 0;
    long nearExpiry = 0;
    long depleted = 0;
    long availableQty = 0;
    long expiredQty = 0;
    BigDecimal availableValue = BigDecimal.ZERO;
    BigDecimal expiredValue = BigDecimal.ZERO;

    LocalDate warningLine = today.plusDays(request.getWithinDays() == null
        ? 30 : request.getWithinDays());

    for (InvBatch batch : batches) {
      int available = batch.getAvailableQuantity() == null ? 0 : batch.getAvailableQuantity();
      BigDecimal unitCost = batch.getUnitCost() == null ? BigDecimal.ZERO : batch.getUnitCost();
      availableQty += available;
      availableValue = availableValue.add(unitCost.multiply(BigDecimal.valueOf(available)));

      if (available == 0) {
        depleted++;
      }
      LocalDate expiry = batch.getExpiryDate();
      if (expiry != null && !expiry.isBefore(today) && !expiry.isAfter(warningLine)) {
        nearExpiry++;
      }
      if (expiry != null && expiry.isBefore(today)) {
        expired++;
        expiredQty += available;
        expiredValue = expiredValue.add(unitCost.multiply(BigDecimal.valueOf(available)));
      }
    }

    InvBatchStatsResponse stats = new InvBatchStatsResponse();
    stats.setTotalBatches((long) batches.size());
    stats.setExpiredBatches(expired);
    stats.setNearExpiryBatches(nearExpiry);
    stats.setDepletedBatches(depleted);
    stats.setAvailableQuantity(availableQty);
    stats.setAvailableValue(availableValue);
    stats.setExpiredQuantity(expiredQty);
    stats.setExpiredValue(expiredValue);
    return stats;
  }

  private LambdaQueryWrapper<InvBatch> buildWrapper(InvBatchQueryRequest request, LocalDate today) {
    LambdaQueryWrapper<InvBatch> wrapper = Wrappers.lambdaQuery();
    wrapper.eq(StringUtils.hasText(request.getSkuId()), InvBatch::getSkuId, request.getSkuId())
        .eq(StringUtils.hasText(request.getWarehouseId()),
            InvBatch::getWarehouseId, request.getWarehouseId())
        .like(StringUtils.hasText(request.getBatchCode()),
            InvBatch::getBatchCode, request.getBatchCode())
        .eq(request.getQualityStatus() != null,
            InvBatch::getQualityStatus, request.getQualityStatus())
        .eq(request.getStatus() != null, InvBatch::getStatus, request.getStatus())
        .ge(request.getExpiryFrom() != null,
            InvBatch::getExpiryDate, request.getExpiryFrom())
        .gt(Boolean.TRUE.equals(request.getHasAvailable()),
            InvBatch::getAvailableQuantity, 0);

    LocalDate expiryTo = request.getExpiryTo();
    if (expiryTo == null && request.getWithinDays() != null) {
      expiryTo = today.plusDays(request.getWithinDays());
    }
    wrapper.le(expiryTo != null, InvBatch::getExpiryDate, expiryTo);

    // 效期升序（空效期排最后）：报表天然关心“先到期的”
    wrapper.orderByAsc(InvBatch::getExpiryDate).orderByAsc(InvBatch::getBatchCode);
    return wrapper;
  }
}
