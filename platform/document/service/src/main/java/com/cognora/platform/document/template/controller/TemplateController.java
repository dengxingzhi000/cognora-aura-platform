package com.cognora.platform.document.template.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.document.template.domain.dto.RenderRequest;
import com.cognora.platform.document.template.domain.dto.RenderResult;
import com.cognora.platform.document.template.domain.entity.DocTemplate;
import com.cognora.platform.document.template.domain.entity.DocTemplateSchema;
import com.cognora.platform.document.template.domain.entity.DocTemplateVersion;
import com.cognora.platform.document.template.service.TemplateRenderService;
import com.cognora.platform.document.template.service.TemplateSchemaService;
import com.cognora.platform.document.template.service.TemplateService;
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
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
@Tag(name = "文档模板", description = "文档模板、变量 Schema 与模板渲染接口。")
public class TemplateController {

    private final TemplateService templateService;
    private final TemplateSchemaService schemaService;
    private final TemplateRenderService renderService;

    @Operation(summary = "分页查询模板", description = "按名称/编码关键字、类型、分类和状态分页查询文档模板。")
    @GetMapping("/page")
    @PreAuthorize("hasAnyAuthority('document:template:view', 'document:template:page')")
