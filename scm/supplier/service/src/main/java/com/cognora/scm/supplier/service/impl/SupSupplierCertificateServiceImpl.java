package com.cognora.scm.supplier.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.common.exception.ServiceException;
import com.cognora.platform.common.log.util.LogUtils;
import com.cognora.scm.supplier.domain.entity.SupSupplier;
import com.cognora.scm.supplier.domain.entity.SupSupplierCertificate;
import com.cognora.scm.supplier.mapper.SupSupplierCertificateMapper;
import com.cognora.scm.supplier.mapper.SupSupplierMapper;
import com.cognora.scm.supplier.service.ISupSupplierCertificateService;
import com.cognora.scm.supplier.service.support.SupplierGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupSupplierCertificateServiceImpl
        extends ServiceImpl<SupSupplierCertificateMapper, SupSupplierCertificate>
        implements ISupSupplierCertificateService {

    /** 到期预警窗口（天）。 */
    public static final int EXPIRY_WARNING_DAYS = 30;

    /** 证照状态：待审核。 */
    private static final int STATUS_PENDING = 0;
    /** 证照状态：有效。 */
    private static final int STATUS_VALID = 1;
    /** 证照状态：临期。 */
    private static final int STATUS_EXPIRING = 2;
    /** 证照状态：已过期。 */
    private static final int STATUS_EXPIRED = 3;
    /** 证照状态：人工驳回。 */
    private static final int STATUS_REJECTED = 4;

    private final SupSupplierMapper supplierMapper;
    private final SupplierGuard supplierGuard;

    @Override
    @Slave
    public List<SupSupplierCertificate> listBySupplierId(String supplierId) {
        supplierGuard.require(supplierId);
        List<SupSupplierCertificate> certificates = lambdaQuery()
                .eq(SupSupplierCertificate::getSupplierId, supplierId)
                .orderByAsc(SupSupplierCertificate::getValidTo)
                .orderByDesc(SupSupplierCertificate::getCreateTime)
                .list();
        LocalDate today = LocalDate.now();
        certificates.forEach(certificate -> fillDerivedFields(certificate, today));
        return certificates;
    }

    @Override
    @Master(reason = "新增供应商资质")
    @Transactional(rollbackFor = Exception.class)
    public SupSupplierCertificate create(String supplierId, SupSupplierCertificate certificate) {
        supplierGuard.require(supplierId);
        validate(certificate);
        certificate.setId(UUID.randomUUID().toString());
        certificate.setSupplierId(supplierId);
        certificate.setTenantId(null);
        certificate.setCreateBy(null);
        certificate.setUpdateBy(null);
        if (certificate.getStatus() == null) {
            certificate.setStatus(resolveStatus(certificate.getValidTo(), LocalDate.now()));
        }
        certificate.setDeleted(false);
        LocalDateTime now = LocalDateTime.now();
        certificate.setCreateTime(now);
        certificate.setUpdateTime(now);
        if (!save(certificate)) {
            throw new ServiceException(500, HttpStatus.INTERNAL_SERVER_ERROR, "资质证照保存失败");
        }
        log.info("新增供应商资质 supplierId={}, certificateId={}",
                LogUtils.sanitizeForLog(supplierId), LogUtils.sanitizeForLog(certificate.getId()));
        return certificate;
    }

    @Override
    @Master(reason = "更新供应商资质")
    @Transactional(rollbackFor = Exception.class)
    public SupSupplierCertificate update(String id, SupSupplierCertificate certificate) {
        SupSupplierCertificate existing = getById(id);
        if (existing == null || Boolean.TRUE.equals(existing.getDeleted())) {
            throw new ServiceException(404, HttpStatus.NOT_FOUND, "资质证照不存在");
        }
        validate(certificate);
        certificate.setId(id);
        certificate.setSupplierId(existing.getSupplierId());
        certificate.setTenantId(null);
        certificate.setCreateBy(null);
        certificate.setCreateTime(null);
        certificate.setUpdateBy(null);
        certificate.setDeleted(null);
        certificate.setUpdateTime(LocalDateTime.now());
        if (certificate.getStatus() == null && certificate.getValidTo() != null) {
            certificate.setStatus(resolveStatus(certificate.getValidTo(), LocalDate.now()));
        }
        if (!updateById(certificate)) {
            throw new ServiceException(404, HttpStatus.NOT_FOUND, "资质证照不存在");
        }
        return getById(id);
    }

    @Override
    @Master(reason = "删除供应商资质")
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(String id) {
        SupSupplierCertificate existing = getById(id);
        if (existing == null || Boolean.TRUE.equals(existing.getDeleted())) {
            return false;
        }
        return removeById(id);
    }

    @Override
    @Master(reason = "审核供应商资质")
    @Transactional(rollbackFor = Exception.class)
    public void verify(String id, boolean approved, String operatorId, String remark) {
        SupSupplierCertificate certificate = getById(id);
        if (certificate == null || Boolean.TRUE.equals(certificate.getDeleted())) {
            throw new ServiceException(404, HttpStatus.NOT_FOUND, "资质证照不存在");
        }
        certificate.setStatus(approved ? resolveStatus(certificate.getValidTo(), LocalDate.now()) : STATUS_REJECTED);
        certificate.setVerifiedBy(operatorId);
        certificate.setVerifiedAt(LocalDateTime.now());
        if (StringUtils.hasText(remark)) {
            certificate.setRemark(remark);
        }
        certificate.setUpdateTime(LocalDateTime.now());
        if (!updateById(certificate)) {
            throw new ServiceException(404, HttpStatus.NOT_FOUND, "资质证照不存在");
        }
    }

    @Override
    @Slave
    public Page<SupSupplierCertificate> pageExpiring(int page, int size, int days) {
        Page<SupSupplierCertificate> result = expiringQuery(days)
                .orderByAsc(SupSupplierCertificate::getValidTo)
                .page(new Page<>(Math.max(page, 1), Math.max(size, 1)));

        List<SupSupplierCertificate> records = result.getRecords();
        LocalDate today = LocalDate.now();
        records.forEach(certificate -> fillDerivedFields(certificate, today));
        Set<String> supplierIds = records.stream()
                .map(SupSupplierCertificate::getSupplierId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (!supplierIds.isEmpty()) {
            Map<String, SupSupplier> suppliers = supplierMapper.selectByIds(supplierIds).stream()
                    .collect(Collectors.toMap(SupSupplier::getId, Function.identity()));
            records.forEach(certificate -> {
                SupSupplier supplier = suppliers.get(certificate.getSupplierId());
                if (supplier != null) {
                    certificate.setSupplierName(supplier.getSupplierName());
                }
            });
        }
        return result;
    }

    @Override
    @Slave
    public long countExpiring(int days) {
        return expiringQuery(days).count();
    }

    private LambdaQueryChainWrapper<SupSupplierCertificate> expiringQuery(int days) {
        LocalDate today = LocalDate.now();
        int boundedDays = Math.clamp(days, 0, 365);
        return lambdaQuery()
                .ge(SupSupplierCertificate::getValidTo, today)
                .le(SupSupplierCertificate::getValidTo, today.plusDays(boundedDays))
                .ne(SupSupplierCertificate::getStatus, STATUS_REJECTED);
    }

    private void fillDerivedFields(SupSupplierCertificate certificate, LocalDate today) {
        if (certificate.getValidTo() == null) {
            certificate.setDaysToExpiry(null);
            return;
        }
        certificate.setDaysToExpiry((int) ChronoUnit.DAYS.between(today, certificate.getValidTo()));
        Integer status = certificate.getStatus();
        // 待审核(0)与人工驳回(4)保持原状态，其余状态按有效期实时刷新
        if (status != null && status != STATUS_PENDING && status != STATUS_REJECTED) {
            certificate.setStatus(resolveStatus(certificate.getValidTo(), today));
        }
    }

    private int resolveStatus(LocalDate validTo, LocalDate today) {
        if (validTo == null) {
            return STATUS_VALID;
        }
        if (validTo.isBefore(today)) {
            return STATUS_EXPIRED;
        }
        if (!validTo.isAfter(today.plusDays(EXPIRY_WARNING_DAYS))) {
            return STATUS_EXPIRING;
        }
        return STATUS_VALID;
    }

    private void validate(SupSupplierCertificate certificate) {
        if (!StringUtils.hasText(certificate.getCertificateType())) {
            throw new ServiceException(400, HttpStatus.BAD_REQUEST, "证照类型不能为空");
        }
        if (!StringUtils.hasText(certificate.getCertificateName())) {
            throw new ServiceException(400, HttpStatus.BAD_REQUEST, "证照名称不能为空");
        }
        if (certificate.getValidFrom() != null && certificate.getValidTo() != null
                && certificate.getValidTo().isBefore(certificate.getValidFrom())) {
            throw new ServiceException(400, HttpStatus.BAD_REQUEST, "证照有效期结束日期不能早于开始日期");
        }
        if (certificate.getStatus() != null
                && (certificate.getStatus() < STATUS_PENDING || certificate.getStatus() > STATUS_REJECTED)) {
            throw new ServiceException(400, HttpStatus.BAD_REQUEST, "证照状态不合法");
        }
    }
}