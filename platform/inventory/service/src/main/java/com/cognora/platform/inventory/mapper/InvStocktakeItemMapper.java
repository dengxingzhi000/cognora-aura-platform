package com.cognora.platform.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cognora.platform.inventory.domain.entity.InvStocktakeItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 盘点明细 Mapper。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Mapper
public interface InvStocktakeItemMapper extends BaseMapper<InvStocktakeItem> {
}
