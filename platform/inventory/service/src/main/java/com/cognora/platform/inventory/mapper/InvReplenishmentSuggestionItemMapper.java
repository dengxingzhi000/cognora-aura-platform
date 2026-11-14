package com.cognora.platform.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 补货建议明细 Mapper。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Mapper
public interface InvReplenishmentSuggestionItemMapper
        extends BaseMapper<InvReplenishmentSuggestionItem> {
}
