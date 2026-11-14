package com.cognora.platform.inventory.service.command;

/**
 * SKU→material 映射解析端口。
 *
 * <p>{@code pur_plan_item} 是 material 口径（{@code material_code/name/unit}），
 * 而补货建议单是 SKU 口径；转采购计划时必须做一次映射。映射的权威来源是商品主数据
 * （{@code platform-product}，{@code prod_sku} 上的 material 字段），
 * 库存服务不直接查它的库，因此抽象成端口。
 *
 * <p>解析不到时返回 {@code null}：设计稿 §8 要求这种情况「转采购计划步骤失败并回写
 * 建议单『待补映射』」，而不是猜一个编码硬转出去——错编码会让采购单买错东西。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public interface SkuMaterialMappingResolver {

  /**
   * 解析 SKU 对应的物料。
   *
   * @param skuId SKU ID
   * @return 映射；无法解析时返回 {@code null}
   */
  SkuMaterialMapping resolve(String skuId);

  /**
   * 桩实现：不做任何映射。
   *
   * <p>生产必须换成查商品主数据的实现；这里留成显式的「无映射」，
   * 让缺失走「待补映射」分支，而不是悄悄用 skuId 冒充 materialCode。
   */
  final class None implements SkuMaterialMappingResolver {

    @Override
    public SkuMaterialMapping resolve(String skuId) {
      return null;
    }
  }
}
