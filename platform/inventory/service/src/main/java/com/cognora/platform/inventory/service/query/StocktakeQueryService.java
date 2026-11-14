package com.cognora.platform.inventory.service.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.inventory.domain.entity.InvStocktake;
import com.cognora.platform.inventory.dto.StocktakeQueryRequest;
import com.cognora.platform.inventory.dto.StocktakeResponse;
import com.cognora.platform.inventory.mapper.InvStocktakeItemMapper;
import com.cognora.platform.inventory.mapper.InvStocktakeMapper;
import com.cognora.platform.inventory.domain.entity.InvStocktakeItem;
import com.cognora.platform.inventory.dto.StocktakeItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 盘点单据分页查询（只读，列表不带明细）。
 *
 * <p>列表刻意不返回明细：一次盘点可能有上千行，列表页只需要头部与差异汇总。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Service
@RequiredArgsConstructor
public class StocktakeQueryService {

  private final InvStocktakeMapper stocktakeMapper;
  private final InvStocktakeItemMapper itemMapper;

  @Slave
  public Page<StocktakeResponse> query(StocktakeQueryRequest request) {
    LambdaQueryWrapper<InvStocktake> wrapper = Wrappers.lambdaQuery();
    wrapper.eq(StringUtils.hasText(request.getWarehouseId()),
            InvStocktake::getWarehouseId, request.getWarehouseId())
        .eq(StringUtils.hasText(request.getStatus()),
            InvStocktake::getStatus, request.getStatus())
        .like(StringUtils.hasText(request.getStocktakeNo()),
            InvStocktake::getStocktakeNo, request.getStocktakeNo())
        .orderByDesc(InvStocktake::getCreateTime);

    Page<InvStocktake> page = stocktakeMapper.selectPage(
        new Page<>(request.getPage(), request.getSize()), wrapper);
    Page<StocktakeResponse> responsePage =
        new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
    responsePage.setRecords(page.getRecords().stream()
        .map(head -> StocktakeResponse.from(head, null))
        .toList());
    return responsePage;
  }

  /** 明细分页，供详情页在单据行数很大时按需加载。 */
  @Slave
  public List<StocktakeItemResponse> items(String stocktakeId) {
    return itemMapper.selectList(
            Wrappers.<InvStocktakeItem>lambdaQuery()
                .eq(InvStocktakeItem::getStocktakeId, stocktakeId)
                .orderByAsc(InvStocktakeItem::getSkuId))
        .stream()
        .map(StocktakeItemResponse::from)
        .toList();
  }
}
