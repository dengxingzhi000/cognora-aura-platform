package com.cognora.platform.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cognora.platform.inventory.domain.entity.InvDemandForecast;
import org.apache.ibatis.annotations.Mapper;

/**
 * 需求预测 Mapper。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Mapper
public interface InvDemandForecastMapper extends BaseMapper<InvDemandForecast> {
}
