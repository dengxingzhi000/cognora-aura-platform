package com.cognora.scm.purchase.service.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.common.log.util.LogUtils;
import com.cognora.scm.purchase.domain.dto.PurchaseInTransitRow;
import com.cognora.scm.purchase.domain.entity.PurOrder;
import com.cognora.scm.purchase.mapper.PurOrderItemMapper;
import com.cognora.scm.purchase.mapper.PurOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 采购订单只读查询。采购单由采购流程写入，前端只消费列表与详情。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurOrderQueryService {

    private final PurOrderMapper purOrderMapper;
    private final PurOrderItemMapper purOrderItemMapper;

    @Slave
    public Page<PurOrder> pageList(int page, int size, String keyword, Integer status, String supplierId) {
        log.debug("分页查询采购订单 page={}, size={}, keyword={}, status={}, supplierId={}",
                page, size, LogUtils.sanitizeForLog(keyword), status, LogUtils.sanitizeForLog(supplierId));

        LambdaQueryWrapper<PurOrder> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PurOrder::getDeleted, false);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(PurOrder::getOrderNo, keyword)
                    .or().like(PurOrder::getSupplierName, keyword));
        }
        if (status != null) {
            wrapper.eq(PurOrder::getStatus, status);
        }
        if (StringUtils.hasText(supplierId)) {
            wrapper.eq(PurOrder::getSupplierId, supplierId);
        }
        wrapper.orderByDesc(PurOrder::getCreateTime);

        return purOrderMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Slave
    public PurOrder getById(String id) {
        return purOrderMapper.selectById(id);
    }

    /**
     * 采购在途量：按 SKU×仓 汇总已下单未收完的数量。
     *
     * <p>供 platform-inventory 的补货计算做在途去重。口径与实现在
     * {@link com.cognora.scm.purchase.mapper.PurOrderItemMapper#sumUnreceivedBySkuAndWarehouse}，
     * 那里有完整的「为什么只算 2/3/4 状态」说明。
     */
    @Slave
    public List<PurchaseInTransitRow> sumUnreceivedInTransit(String warehouseId) {
        log.debug("查询采购在途量 warehouseId={}", LogUtils.sanitizeForLog(warehouseId));
        return purOrderItemMapper.sumUnreceivedBySkuAndWarehouse(warehouseId);
    }
}