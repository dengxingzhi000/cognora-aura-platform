package com.cognora.platform.document.document.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.document.document.domain.dto.DocumentStatusChangeRequest;
import com.cognora.platform.document.document.domain.entity.DocDocument;
import com.cognora.platform.document.document.domain.entity.DocDocumentAudit;
import com.cognora.platform.document.document.domain.entity.DocDocumentVersion;
import com.cognora.platform.document.document.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "文档管理", description = "文档的分页查询、详情与创建接口。")
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "分页查询文档", description = "按名称/编码关键字、业务类型和状态分页查询文档。")
    @GetMapping("/page")
    @PreAuthorize("hasAnyAuthority('document:document:view', 'document:document:page')")
