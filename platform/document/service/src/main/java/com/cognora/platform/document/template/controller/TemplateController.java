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
    @PreAuthorize("hasAnyAuthority('document:template:page', 'document:template:view')")
    public ApiResponse<Page<DocTemplate>> page(
            @Parameter(description = "页码，默认 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页数量，默认 20") @RequestParam(required = false) Integer size,
            @Parameter(description = "名称或编码关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "模板类型") @RequestParam(required = false) String templateType,
            @Parameter(description = "分类") @RequestParam(required = false) String category,
            @Parameter(description = "状态") @RequestParam(required = false) String status) {
        return ApiResponse.success(templateService.pageQuery(
                page == null ? 1 : page, size == null ? 20 : size, keyword, templateType, category, status));
    }

    @Operation(summary = "查询模板", description = "根据模板 ID 查询当前租户的文档模板详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('document:template:view', 'document:template:page')")
    public ApiResponse<DocTemplate> getById(@Parameter(description = "模板 ID") @PathVariable String id) {
        return templateService.findCurrentTenant(id)
                .<ApiResponse<DocTemplate>>map(ApiResponse::success)
                .orElseGet(ApiResponse::success);
    }

    @Operation(summary = "查询模板版本", description = "根据模板 ID 查询该模板的历史版本列表。")
    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('document:template:list-versions')")
    public ApiResponse<List<DocTemplateVersion>> listVersions(@Parameter(description = "模板 ID") @PathVariable String id) {
        return ApiResponse.success(templateService.listVersions(id));
    }

    @Operation(summary = "创建模板", description = "新建并保存一个文档模板，初始状态为草稿。")
    @PostMapping
    @PreAuthorize("hasAuthority('document:template:create')")
    @AuditLog(operation = "创建文档模板", businessType = "DOCUMENT", riskLevel = 3)
    public ApiResponse<DocTemplate> create(@RequestBody DocTemplate template) {
        return ApiResponse.success(templateService.createTemplate(template));
    }

    @Operation(summary = "更新模板", description = "更新模板名称、类型、分类、格式、描述或状态。")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('document:template:update')")
    @AuditLog(operation = "修改文档模板", businessType = "DOCUMENT", riskLevel = 3)
    public ApiResponse<DocTemplate> update(@Parameter(description = "模板 ID") @PathVariable String id,
                                           @RequestBody DocTemplate template) {
        return ApiResponse.success(templateService.updateTemplate(id, template));
    }

    @Operation(summary = "创建模板版本", description = "为模板创建新版本，版本号自动递增，初始状态为草稿。")
    @PostMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('document:template:create-version')")
    @AuditLog(operation = "创建模板版本", businessType = "DOCUMENT", riskLevel = 3)
    public ApiResponse<DocTemplateVersion> createVersion(@Parameter(description = "模板 ID") @PathVariable String id,
                                                         @RequestBody DocTemplateVersion version) {
        return ApiResponse.success(templateService.createVersion(id, version));
    }

    @Operation(summary = "发布模板版本", description = "发布指定版本，归档旧的已发布版本并更新模板当前版本。")
    @PostMapping("/{id}/versions/{versionId}/publish")
    @PreAuthorize("hasAuthority('document:template:publish-version')")
    @AuditLog(operation = "发布模板版本", businessType = "DOCUMENT", riskLevel = 3)
    public ApiResponse<DocTemplateVersion> publishVersion(@Parameter(description = "模板 ID") @PathVariable String id,
                                                          @Parameter(description = "模板版本 ID") @PathVariable String versionId) {
        return ApiResponse.success(templateService.publishVersion(id, versionId));
    }

    // ---- 变量 Schema ----

    @Operation(summary = "保存模板变量 Schema", description = "带 id 时更新已有 Schema，否则新建；变量定义需为合法 JSON 数组。")
    @PostMapping("/{templateId}/schema")
    @PreAuthorize("hasAuthority('document:template:save-schema')")
    @AuditLog(operation = "保存模板变量 Schema", businessType = "DOCUMENT", riskLevel = 3)
    public ApiResponse<DocTemplateSchema> saveSchema(@Parameter(description = "模板 ID") @PathVariable String templateId,
                                                     @RequestBody DocTemplateSchema schema) {
        return ApiResponse.success(schemaService.saveSchema(templateId, schema));
    }

    @Operation(summary = "查询模板变量 Schema", description = "根据模板 ID 查询该模板的全部变量 Schema。")
    @GetMapping("/{templateId}/schema")
    @PreAuthorize("hasAuthority('document:template:get-schema')")
    public ApiResponse<List<DocTemplateSchema>> listSchema(@Parameter(description = "模板 ID") @PathVariable String templateId) {
        return ApiResponse.success(schemaService.listByTemplate(templateId));
    }

    // ---- 渲染入口 ----

    @Operation(summary = "渲染模板", description = "按模板和变量数据渲染生成文档。")
    @PostMapping("/render")
    @PreAuthorize("hasAuthority('document:template:render')")
    @AuditLog(operation = "渲染模板生成文档", businessType = "DOCUMENT", riskLevel = 3)
    public ApiResponse<RenderResult> render(@RequestBody RenderRequest request) {
        return ApiResponse.success(renderService.render(request));
    }
}
