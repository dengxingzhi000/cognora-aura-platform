package com.cognora.scm.supplier.service.support;

import com.cognora.platform.common.exception.ServiceException;
import com.cognora.scm.supplier.domain.entity.SupSupplier;
import com.cognora.scm.supplier.mapper.SupSupplierMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 供应商主档校验：各子域（资质/联系人/银行账户/评价）在写入前统一校验供应商存在且未删除。
 *
 * <p>此前这段逻辑在四个 ServiceImpl 里逐字重复，属于同一段校验的多个副本；集中到这里后
 * 状态判断与错误码只有一处。</p>
 */
@Component
@RequiredArgsConstructor
public class SupplierGuard {

    private final SupSupplierMapper supplierMapper;

    /**
     * 校验供应商存在且未被逻辑删除，返回供应商主档。
     *
     * @param supplierId 供应商ID
     * @return 供应商主档
     * @throws ServiceException ID为空返回 400，供应商不存在或已删除返回 404
     */
    public SupSupplier require(String supplierId) {
        if (!StringUtils.hasText(supplierId)) {
            throw new ServiceException(400, HttpStatus.BAD_REQUEST, "供应商ID不能为空");
        }
        SupSupplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null || Boolean.TRUE.equals(supplier.getDeleted())) {
            throw new ServiceException(404, HttpStatus.NOT_FOUND, "供应商不存在");
        }
        return supplier;
    }
}
