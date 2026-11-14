package com.cognora.platform.document.file.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.document.file.domain.entity.DocumentFile;
import com.cognora.platform.document.file.service.DocumentFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/document-files")
@RequiredArgsConstructor
@Tag(name = "文档文件", description = "文档文件元数据、访问地址与删除接口。")
public class DocumentFileController {

    private final DocumentFileService documentFileService;

    @Operation(summary = "分页查询文档文件", description = "按名称关键字、来源类型和关联业务 ID 分页查询当前租户的文档文件。")
    @GetMapping("/page")
    @PreAuthorize("hasAnyAuthority('document:file:view', 'document:file:page')")
}