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
    @PreAuthorize("hasAnyAuthority('product:category:search', 'product:category:view')")
