package com.cognora.platform.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.domain.PageResult;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.product.domain.entity.ProdCategory;
import com.cognora.platform.product.service.IProdCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/prod-category")
@RequiredArgsConstructor
@Tag(name = "商品分类", description = "商品分类树的查询、搜索、子分类获取与增删改接口。")
public class ProdCategoryController {
    private final IProdCategoryService categoryService;

    @Operation(summary = "分页查询分类", description = "按分类名称模糊筛选并分页查询未删除的商品分类，结果按排序号升序排列。")
    @GetMapping
    @PreAuthorize("hasAuthority('product:category:list')")
    public ApiResponse<PageResult<ProdCategory>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "分类名称关键字，可选，支持模糊匹配")
            @RequestParam(required = false) String name) {

        Page<ProdCategory> result = categoryService.pageByName(name, page, size);
        return ApiResponse.success(PageResult.of(result));
    }

    @Operation(summary = "查询分类详情", description = "根据分类ID查询商品分类的详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('product:category:view')")
    public ApiResponse<ProdCategory> getById(@Parameter(description = "分类ID") @PathVariable String id) {
        ProdCategory category = categoryService.getById(id);
        return ApiResponse.success(category);
    }

    @Operation(summary = "查询分类树", description = "查询完整的商品分类树形结构。")
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('product:category:get-category-tree')")
    public ApiResponse<List<ProdCategory>> getCategoryTree() {
        List<ProdCategory> tree = categoryService.getCategoryTree();
        return ApiResponse.success(tree);
    }

    @Operation(summary = "查询子分类", description = "查询指定父分类下的直接子分类列表。")
    @GetMapping("/children/{parentId}")
    @PreAuthorize("hasAuthority('product:category:list-by-parent-id')")
    public ApiResponse<List<ProdCategory>> listByParentId(@Parameter(description = "父分类ID") @PathVariable String parentId) {
        List<ProdCategory> children = categoryService.listByParentId(parentId);
        return ApiResponse.success(children);
    }

    @Operation(summary = "搜索分类", description = "根据分类名称模糊搜索商品分类列表。")
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('product:category:search')")
    public ApiResponse<List<ProdCategory>> search(
            @Parameter(description = "分类名称关键字，可选，支持模糊匹配")
            @RequestParam(required = false) String name) {
        List<ProdCategory> result = categoryService.searchByName(name);
        return ApiResponse.success(result);
    }

    @Operation(summary = "创建分类", description = "新增商品分类，自动生成ID并初始化创建与更新时间。")
    @PostMapping
    @PreAuthorize("hasAuthority('product:category:create')")
    @AuditLog(operation = "创建商品分类", businessType = "PRODUCT", riskLevel = 3)
    public ApiResponse<Void> create(@RequestBody ProdCategory category) {
        categoryService.createCategory(category);
        return ApiResponse.success();
    }

    @Operation(summary = "更新分类", description = "根据分类ID更新商品分类信息并刷新更新时间。")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('product:category:update')")
    @AuditLog(operation = "修改商品分类", businessType = "PRODUCT", riskLevel = 3)
    public ApiResponse<Void> update(@Parameter(description = "分类ID") @PathVariable String id, @RequestBody ProdCategory category) {
        categoryService.updateCategory(id, category);
        return ApiResponse.success();
    }

    @Operation(summary = "删除分类", description = "根据分类ID逻辑删除商品分类。")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('product:category:delete')")
    @AuditLog(operation = "删除商品分类", businessType = "PRODUCT", riskLevel = 4)
    public ApiResponse<Void> delete(@Parameter(description = "分类ID") @PathVariable String id) {
        categoryService.deleteCategory(id);
        return ApiResponse.success();
    }
}
