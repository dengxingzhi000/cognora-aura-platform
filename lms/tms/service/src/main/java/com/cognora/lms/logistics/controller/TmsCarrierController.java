package com.cognora.lms.logistics.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.lms.logistics.domain.entity.TmsCarrier;
import com.cognora.lms.logistics.service.ITmsCarrierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/v1/tms-carrier")
@Tag(name = "承运商管理", description = "运输承运商档案的维护、启用停用及分页查询接口。")
public class TmsCarrierController {

    private final ITmsCarrierService carrierService;

    @Operation(summary = "查询承运商详情", description = "根据承运商主键 ID 获取承运商完整信息。")
    @GetMapping("/{id}")
    @AuditLog(operation = "更新承运商", businessType = "LOGISTICS", riskLevel = 3, recordParams = false)
}