package com.cognora.platform.inventory.service.replenishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 采购在途量的 HTTP 实现：经 Nacos 服务发现调用 scm-purchase 的
 * {@code GET /api/v1/pur-order/in-transit}。
 *
 * <p><strong>为什么走 HTTP 而不是 Dubbo：</strong>采购侧该查询是纯只读聚合，
 * 且 {@code PurchaseDubboService} 现有方法是写路径（创建申请/确认收货），
 * 为一个读接口扩 Dubbo 契约会把两边的发布节奏绑在一起。HTTP 只读端点配合
 * {@code RestClient} 的负载均衡即可，失败可以直接降级。
 *
 * <p><strong>降级：</strong>下游不可用时抛 {@link RuntimeException}，由
 * {@code InTransitQuantityResolver} 捕获并退回「仅建议在途」。这里刻意不吞异常：
 * 让调用方统一决定降级口径，避免出现两处不同的失败处理。
 *
 * <p><strong>响应形状：</strong>{@code ApiResponse<List<PurchaseInTransitRow>>}，
 * 即 {@code {code, message, data:[{skuId, warehouseId, inTransitQty}]}}。按
 * {@code JsonNode} 解析而不是绑定 DTO：跨服务的响应结构只需读三个字段，
 * 绑定类型会让库存服务反向依赖采购的 DTO 包。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
public class HttpPurchaseInTransitClient implements PurchaseInTransitPort {

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public HttpPurchaseInTransitClient(RestClient restClient, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public Map<String, Integer> unreceivedQuantities() {
    String body = restClient.get()
        .uri("/api/v1/pur-order/in-transit")
        .retrieve()
        .body(String.class);

    Map<String, Integer> result = new HashMap<>();
    if (body == null || body.isBlank()) {
      return result;
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode data = root.get("data");
      if (data == null || !data.isArray()) {
        log.debug("采购在途响应没有 data 数组，视为空");
        return result;
      }
      for (JsonNode row : data) {
        String skuId = text(row, "skuId");
        String warehouseId = text(row, "warehouseId");
        String key = InTransitQuantities.key(skuId, warehouseId);
        if (key == null) {
          continue;
        }
        JsonNode qtyNode = row.get("inTransitQty");
        int qty = qtyNode == null || qtyNode.isNull() ? 0 : qtyNode.asInt(0);
        if (qty > 0) {
          result.merge(key, qty, Integer::sum);
        }
      }
    } catch (Exception e) {
      // 解析失败等同于下游不可用：抛出去让 resolver 降级，不要返回半份数据
      throw new IllegalStateException("采购在途响应解析失败: " + e.getMessage(), e);
    }
    log.debug("采购在途量: rows={}", result.size());
    return result;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  /** 供配置类构造 Bean 时使用：默认指向采购服务的注册名。 */
  public static final String DEFAULT_SERVICE_ID = "scm-purchase";
}
