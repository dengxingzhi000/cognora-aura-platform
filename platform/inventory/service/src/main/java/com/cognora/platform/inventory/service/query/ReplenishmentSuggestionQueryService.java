package com.cognora.platform.inventory.service.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.inventory.domain.entity.InvDemandForecast;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionItemResponse;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionQueryRequest;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionResponse;
import com.cognora.platform.inventory.mapper.InvDemandForecastMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionItemMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 补货建议单与需求预测的只读查询。
 *
 * <p>列表刻意不带明细：一张建议单可能有上千行，列表页只需要头部与汇总；
 * 明细走 {@link #items(String)} 按需加载。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Service
@RequiredArgsConstructor
public class ReplenishmentSuggestionQueryService {

  private final InvReplenishmentSuggestionMapper suggestionMapper;
  private final InvReplenishmentSuggestionItemMapper itemMapper;
  private final InvDemandForecastMapper forecastMapper;

  @Slave
  public Page<ReplenishmentSuggestionResponse> query(ReplenishmentSuggestionQueryRequest request) {
    LambdaQueryWrapper<InvReplenishmentSuggestion> wrapper = Wrappers.lambdaQuery();
    wrapper.eq(StringUtils.hasText(request.getWarehouseId()),
            InvReplenishmentSuggestion::getWarehouseId, request.getWarehouseId())
        .eq(StringUtils.hasText(request.getStatus()),
            InvReplenishmentSuggestion::getStatus, request.getStatus())
        .eq(StringUtils.hasText(request.getPeriod()),
            InvReplenishmentSuggestion::getPeriod, request.getPeriod())
        .like(StringUtils.hasText(request.getSuggestionNo()),
            InvReplenishmentSuggestion::getSuggestionNo, request.getSuggestionNo())
        .orderByDesc(InvReplenishmentSuggestion::getCreateTime);

    Page<InvReplenishmentSuggestion> page = suggestionMapper.selectPage(
        new Page<>(request.getPage(), request.getSize()), wrapper);
    Page<ReplenishmentSuggestionResponse> responsePage =
        new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
    responsePage.setRecords(page.getRecords().stream()
        .map(head -> ReplenishmentSuggestionResponse.from(head, null))
        .toList());
    return responsePage;
  }

  /** 明细按需加载。 */
  @Slave
  public List<ReplenishmentSuggestionItemResponse> items(String suggestionId) {
    return itemMapper.selectList(
            Wrappers.<InvReplenishmentSuggestionItem>lambdaQuery()
                .eq(InvReplenishmentSuggestionItem::getSuggestionId, suggestionId)
                .orderByAsc(InvReplenishmentSuggestionItem::getSkuId))
        .stream()
        .map(ReplenishmentSuggestionItemResponse::from)
        .toList();
  }

  /**
   * 某 SKU×仓 的最新一版预测，用于「这个数怎么来的」的解释接口。
   *
   * @return 最新一版；没有任何预测时返回 {@code null}
   */
  @Slave
  public InvDemandForecast latestForecast(String skuId, String warehouseId) {
    return forecastMapper.selectOne(
        Wrappers.<InvDemandForecast>lambdaQuery()
            .eq(InvDemandForecast::getSkuId, skuId)
            .eq(StringUtils.hasText(warehouseId), InvDemandForecast::getWarehouseId, warehouseId)
            .orderByDesc(InvDemandForecast::getForecastDate)
            .orderByDesc(InvDemandForecast::getVersion)
            .last("LIMIT 1"));
  }
}
