package com.cognora.platform.inventory.service.command;

/**
 * SKU → material 映射结果。
 *
 * @param materialCode    物料编码（{@code pur_plan_item.material_code}）
 * @param materialName    物料名称
 * @param materialCategory 物料分类
 * @param unit            计量单位
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public record SkuMaterialMapping(String materialCode, String materialName,
                                 String materialCategory, String unit) {
}
