package com.cognora.platform.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 补货建议单头 Mapper。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Mapper
public interface InvReplenishmentSuggestionMapper extends BaseMapper<InvReplenishmentSuggestion> {
}
