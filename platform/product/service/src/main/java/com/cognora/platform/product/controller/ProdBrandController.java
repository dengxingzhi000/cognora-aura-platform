package com.cognora.platform.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.domain.PageResult;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.product.domain.entity.ProdBrand;
import com.cognora.platform.product.service.IProdBrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/prod-brand")
@RequiredArgsConstructor
@Tag(name = "品牌管理", description = "商品品牌的查询、搜索、推荐列表维护与增删改接口。")
public class ProdBrandController {
    private final IProdBrandService brandService;

    @Operation(summary = "分页查询品牌", description = "按品牌名称模糊筛选并分页查询未删除的品牌，结果按排序号升序排列。")
    @GetMapping
    @PreAuthorize("hasAuthority('product:brand:list')")
    public ApiResponse<PageResult<ProdBrand>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "品牌名称关键字，可选，支持模糊匹配")
            @RequestParam(required = false) String name) {

        Page<ProdBrand> result = brandService.pageByName(name, page, size);
        return ApiResponse.success(PageResult.of(result));
    }

    @Operation(summary = "查询品牌详情", description = "根据品牌ID查询品牌的详细信息。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('product:brand:list-featured', 'product:brand:view')")
