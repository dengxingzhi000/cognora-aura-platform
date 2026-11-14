package com.cognora.platform.inventory.service.replenishment;

import com.cognora.platform.inventory.service.command.SkuMaterialMapping;
import com.cognora.platform.inventory.service.command.SkuMaterialMappingResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

/**
 * 基于商品主数据的 SKU→material 映射。
 *
 * <p>调用 {@code platform-product} 的只读端点 {@code GET /api/v1/prod-sku/{id}}，
 * 把 SKU 转成 {@code pur_plan_item} 需要的 material 口径。
 *
 * <p><strong>映射口径（重要）：</strong>{@code prod_sku} 没有独立的 material 字段，
 * 因此：
 * <ul>
 *   <li>{@code materialCode} ← {@code skuCode}（形如 {@code SKU-IP15P-128}，
 *       本身就是采购侧可识别的物料编码，且全局唯一）；</li>
 *   <li>{@code materialName} ← {@code skuName}（{@code pur_plan_item.material_name} 是
 *       NOT NULL，必须有值）；</li>
 *   <li>{@code materialCategory} ← 商品分类名（缺失时留空）；</li>
 *   <li>{@code unit} ← 固定「件」（{@code prod_sku} 没有计量单位字段）。</li>
 * </ul>
 * 这套映射是<strong>推断</strong>而非主数据契约：如果后续商品域引入真正的
 * material 主子关系，应替换本类而不改调用方（{@link SkuMaterialMappingResolver} 是端口）。
 *
 * <p><strong>失败语义：</strong>查不到 / 调用失败 / {@code skuCode} 为空时返回
 * {@code null}，由 {@code ReplenishmentSuggestionCommandService} 转成
 * 「待补映射」并让 convert 失败。绝不回退成「用 skuId 冒充 materialCode」——
 * 那会让采购单买错东西，而且错误要到收货时才发现。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
public class ProductSkuMaterialMappingResolver implements SkuMaterialMappingResolver {

  /** 默认计量单位：prod_sku 不携带单位。 */
  private static final String DEFAULT_UNIT = "件";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public ProductSkuMaterialMappingResolver(RestClient restClient, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public SkuMaterialMapping resolve(String skuId) {
    if (skuId == null || skuId.isBlank()) {
      return null;
    }
    String body;
    try {
      body = restClient.get()
          .uri("/api/v1/prod-sku/{id}", skuId)
          .retrieve()
          .body(String.class);
    } catch (RuntimeException e) {
      // 404 与网络故障一样处理：映射拿不到就走「待补映射」，不阻断整张建议单
      log.warn("查询 SKU 主数据失败，视为映射缺失: skuId={}, error={}", skuId, e.getMessage());
      return null;
    }
    if (body == null || body.isBlank()) {
      return null;
    }

    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode data = root.get("data");
      if (data == null || data.isNull() || !data.isObject()) {
        return null;
      }
      String skuCode = text(data, "skuCode");
      String skuName = text(data, "skuName");
      if (skuCode == null || skuCode.isBlank()) {
        log.debug("SKU 缺少 skuCode，无法作为 materialCode: skuId={}", skuId);
        return null;
      }
      return new SkuMaterialMapping(
          skuCode,
          skuName == null || skuName.isBlank() ? skuCode : skuName,
          null,
          DEFAULT_UNIT);
    } catch (Exception e) {
      log.warn("解析 SKU 主数据失败，视为映射缺失: skuId={}, error={}", skuId, e.getMessage());
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  public static final String DEFAULT_SERVICE_ID = "platform-product";
}
