package com.cognora.platform.inventory.service.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentPolicy;
import com.cognora.platform.inventory.dto.ReplenishmentPolicyResponse;
import com.cognora.platform.inventory.mapper.InvReplenishmentPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 补货策略分页查询（只读）。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Service
@RequiredArgsConstructor
public class ReplenishmentPolicyQueryService {

  private final InvReplenishmentPolicyMapper policyMapper;

  @Slave
  public Page<ReplenishmentPolicyResponse> query(String warehouseId, String skuId,
                                                 int page, int size) {
    LambdaQueryWrapper<InvReplenishmentPolicy> wrapper = Wrappers.lambdaQuery();
    wrapper.eq(StringUtils.hasText(warehouseId),
            InvReplenishmentPolicy::getWarehouseId, warehouseId)
        .eq(StringUtils.hasText(skuId), InvReplenishmentPolicy::getSkuId, skuId)
        .orderByDesc(InvReplenishmentPolicy::getUpdateTime);
    Page<InvReplenishmentPolicy> result = policyMapper.selectPage(new Page<>(page, size), wrapper);
    Page<ReplenishmentPolicyResponse> response = new Page<>(result.getCurrent(), result.getSize(),
        result.getTotal());
    response.setRecords(result.getRecords().stream()
        .map(ReplenishmentPolicyResponse::from).toList());
    return response;
  }

  @Slave
  public ReplenishmentPolicyResponse getById(String id) {
    InvReplenishmentPolicy policy = policyMapper.selectById(id);
    return policy == null ? null : ReplenishmentPolicyResponse.from(policy);
  }
}
