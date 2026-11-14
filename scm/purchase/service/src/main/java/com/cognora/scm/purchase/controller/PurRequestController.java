package com.cognora.scm.purchase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.exception.ServiceException;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.common.web.domain.SecurityUser;
import com.cognora.platform.common.web.util.SecurityUtils;
import com.cognora.scm.purchase.domain.dto.PurRequestConvertDTO;
import com.cognora.scm.purchase.domain.dto.PurRequestDetailDTO;
import com.cognora.scm.purchase.domain.dto.PurRequestUpsertDTO;
import com.cognora.scm.purchase.domain.entity.PurOrder;
import com.cognora.scm.purchase.domain.entity.PurRequest;
import com.cognora.scm.purchase.service.IPurRequestItemService;
import com.cognora.scm.purchase.service.IPurRequestService;
import com.cognora.scm.purchase.service.command.PurRequestCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
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
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/pur-request")
@Tag(name = "采购申请", description = "采购申请的草稿维护、提交、审批与转采购单。")
public class PurRequestController {

    private final IPurRequestService purRequestService;
    private final IPurRequestItemService purRequestItemService;
    private final PurRequestCommandService purRequestCommandService;

    @Operation(summary = "根据 ID 查询采购申请", description = "按主键查询单张采购申请单的完整信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('purchase:request:list-by-status', 'purchase:request:view')")
