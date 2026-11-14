package com.cognora.scm.supplier.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.log.util.LogUtils;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.scm.supplier.domain.entity.SupSupplier;
import com.cognora.scm.supplier.domain.entity.SupSupplierCategory;
import com.cognora.scm.supplier.domain.entity.SupSupplierChangeLog;
import com.cognora.scm.supplier.dto.SupplierAuditRequest;
import com.cognora.scm.supplier.dto.SupplierPerformanceVO;
import com.cognora.scm.supplier.dto.SupplierProfileVO;
import com.cognora.scm.supplier.dto.SupplierStatsVO;
import com.cognora.scm.supplier.dto.SupplierUpsertRequest;
import com.cognora.scm.supplier.service.ISupSupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/v1/suppliers")
@Tag(name = "供应商管理", description = "供应商主数据、准入审核、黑名单、分类、绩效与变更留痕。")
public class SupSupplierController {

    private final ISupSupplierService supplierService;

    @Operation(summary = "查询供应商详情", description = "根据供应商ID查询未删除的供应商详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('supplier:supplier:search-by-name', 'supplier:supplier:view')")
}