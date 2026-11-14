package com.cognora.platform.inventory.service.command;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.exception.ServiceException;
import com.cognora.platform.common.response.ResultCode;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentPolicy;
import com.cognora.platform.inventory.dto.ReplenishmentPolicyRequest;
import com.cognora.platform.inventory.dto.ReplenishmentPolicyResponse;
import com.cognora.platform.inventory.mapper.InvReplenishmentPolicyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补货策略维护。
 *
 * <p>upsert 语义：同一 {@code tenant+sku+warehouse} 已存在就更新，否则新建。
 * 策略是「运营随时会调」的配置，让它幂等可以减少一次「先查再建」的往返与竞态。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplenishmentPolicyCommandService {

  private final InvReplenishmentPolicyMapper policyMapper;

  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public ReplenishmentPolicyResponse upsert(ReplenishmentPolicyRequest request) {
    if (request == null || request.getSkuId() == null || request.getSkuId().isBlank()) {
      throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
          "SKU 不能为空");
    }
    if (request.getWarehouseId() == null || request.getWarehouseId().isBlank()) {
      throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
          "仓库不能为空");
    }

    InvReplenishmentPolicy existing = policyMapper.selectOne(
        new LambdaQueryWrapper<InvReplenishmentPolicy>()
            .eq(InvReplenishmentPolicy::getSkuId, request.getSkuId())
            .eq(InvReplenishmentPolicy::getWarehouseId, request.getWarehouseId())
            .eq(InvReplenishmentPolicy::getDeleted, false)
            .last("LIMIT 1"));

    InvReplenishmentPolicy policy = existing != null ? existing : new InvReplenishmentPolicy();
    policy.setSkuId(request.getSkuId());
    policy.setWarehouseId(request.getWarehouseId());

    String abc = request.getAbcClass() == null ? null : request.getAbcClass().trim().toUpperCase();
    if (abc != null && !abc.isEmpty()) {
      if (!"A".equals(abc) && !"B".equals(abc) && !"C".equals(abc)) {
        throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
            "ABC 分级只能是 A/B/C: " + request.getAbcClass());
      }
      policy.setAbcClass(abc);
    } else if (existing == null) {
      policy.setAbcClass("C");
    }

    policy.setServiceLevel(request.getServiceLevel() != null
        ? request.getServiceLevel() : defaultServiceLevel(policy.getAbcClass()));
    if (request.getLeadTimeDays() != null) {
      policy.setLeadTimeDays(request.getLeadTimeDays());
    } else if (existing == null) {
      policy.setLeadTimeDays(7);
    }
    if (request.getReviewPeriodDays() != null) {
      policy.setReviewPeriodDays(request.getReviewPeriodDays());
    } else if (existing == null) {
      policy.setReviewPeriodDays(7);
    }
    if (request.getSafetyDays() != null) {
      policy.setSafetyDays(request.getSafetyDays());
    } else if (existing == null) {
      policy.setSafetyDays(new BigDecimal("3"));
    }
    policy.setMinOrderQty(request.getMinOrderQty() != null
        ? request.getMinOrderQty() : (existing == null ? 1 : policy.getMinOrderQty()));
    policy.setPackMultiple(request.getPackMultiple() != null
        ? request.getPackMultiple() : (existing == null ? 1 : policy.getPackMultiple()));
    if (request.getSupplierId() != null) {
      policy.setSupplierId(request.getSupplierId());
    }
    policy.setManualOverride(request.getManualOverride() != null
        ? request.getManualOverride() : (existing == null ? Boolean.FALSE : policy.getManualOverride()));
    policy.setEnabled(request.getEnabled() != null
        ? request.getEnabled() : (existing == null ? Boolean.TRUE : policy.getEnabled()));
    if (request.getRemark() != null) {
      policy.setRemark(request.getRemark());
    }
    policy.setUpdateTime(LocalDateTime.now());

    if (existing == null) {
      policy.setCreateTime(LocalDateTime.now());
      policy.setDeleted(false);
      policyMapper.insert(policy);
    } else {
      policyMapper.updateById(policy);
    }
    log.info("补货策略已保存: skuId={}, warehouseId={}, leadTime={}d, review={}d",
        policy.getSkuId(), policy.getWarehouseId(), policy.getLeadTimeDays(),
        policy.getReviewPeriodDays());
    return ReplenishmentPolicyResponse.from(policy);
  }

  @Master(reason = "删除补货策略")
  @Transactional(rollbackFor = Exception.class)
  public boolean remove(String id) {
    return policyMapper.deleteById(id) > 0;
  }

  /** ABC 默认服务水平：A=98% B=95% C=90%（设计稿 §4.2）。 */
  static BigDecimal defaultServiceLevel(String abcClass) {
    if ("A".equals(abcClass)) {
      return new BigDecimal("0.9800");
    }
    if ("B".equals(abcClass)) {
      return new BigDecimal("0.9500");
    }
    return new BigDecimal("0.9000");
  }
}
