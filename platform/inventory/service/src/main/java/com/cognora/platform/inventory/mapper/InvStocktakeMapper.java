package com.cognora.platform.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cognora.platform.inventory.domain.entity.InvStocktake;
import org.apache.ibatis.annotations.Mapper;

/**
 * 盘点单据头 Mapper。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Mapper
public interface InvStocktakeMapper extends BaseMapper<InvStocktake> {
}
