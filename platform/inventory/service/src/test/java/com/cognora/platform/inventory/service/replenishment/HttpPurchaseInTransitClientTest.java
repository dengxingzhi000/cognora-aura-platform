package com.cognora.platform.inventory.service.replenishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 采购在途客户端用例：解析、口径、失败语义。
 */
class HttpPurchaseInTransitClientTest {

  private static final String BASE = "http://scm-purchase";
  private static final String URL = BASE + "/api/v1/pur-order/in-transit";

  private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
  private final MockRestServiceServer server =
      MockRestServiceServer.bindTo(builder).build();
  private final HttpPurchaseInTransitClient client =
      new HttpPurchaseInTransitClient(builder.build(), new ObjectMapper());

  @Test
  @DisplayName("把 ApiResponse.data 聚合成 skuId|warehouseId → 数量")
  void parsesEnvelopeAndAggregates() {
    server.expect(requestTo(URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("""
            {"code":200,"message":"ok","data":[
              {"skuId":"sku-1","warehouseId":"wh-1","inTransitQty":30},
              {"skuId":"sku-2","warehouseId":"wh-1","inTransitQty":5}
            ]}
            """, MediaType.APPLICATION_JSON));

    Map<String, Integer> result = client.unreceivedQuantities();

    assertThat(result)
        .containsEntry("sku-1|wh-1", 30)
        .containsEntry("sku-2|wh-1", 5)
        .hasSize(2);
  }

  @Test
  @DisplayName("同一 SKU×仓 出现多行时累加，不覆盖")
  void mergesDuplicateRows() {
    server.expect(requestTo(URL)).andRespond(withSuccess("""
        {"code":200,"data":[
          {"skuId":"sku-1","warehouseId":"wh-1","inTransitQty":10},
          {"skuId":"sku-1","warehouseId":"wh-1","inTransitQty":15}
        ]}
        """, MediaType.APPLICATION_JSON));

    assertThat(client.unreceivedQuantities()).containsEntry("sku-1|wh-1", 25);
  }

  @Test
  @DisplayName("数量为 0 / 负数 / 缺字段的行被忽略，不产生假在途")
  void skipsZeroAndNegativeQuantities() {
    server.expect(requestTo(URL)).andRespond(withSuccess("""
        {"code":200,"data":[
          {"skuId":"sku-1","warehouseId":"wh-1","inTransitQty":0},
          {"skuId":"sku-2","warehouseId":"wh-1","inTransitQty":-3},
          {"skuId":"sku-3","warehouseId":"wh-1"}
        ]}
        """, MediaType.APPLICATION_JSON));

    assertThat(client.unreceivedQuantities()).isEmpty();
  }

  @Test
  @DisplayName("缺 skuId 或 warehouseId 的行无法组键，直接跳过")
  void skipsRowsWithoutKey() {
    server.expect(requestTo(URL)).andRespond(withSuccess("""
        {"code":200,"data":[
          {"skuId":null,"warehouseId":"wh-1","inTransitQty":10},
          {"skuId":"sku-1","warehouseId":null,"inTransitQty":10}
        ]}
        """, MediaType.APPLICATION_JSON));

    assertThat(client.unreceivedQuantities()).isEmpty();
  }

  @Test
  @DisplayName("没有 data 数组时返回空表，而不是抛错")
  void toleratesMissingDataArray() {
    server.expect(requestTo(URL)).andRespond(withSuccess(
        "{\"code\":500,\"message\":\"boom\"}", MediaType.APPLICATION_JSON));

    assertThat(client.unreceivedQuantities()).isEmpty();
  }

  @Test
  @DisplayName("空响应体返回空表")
  void toleratesEmptyBody() {
    server.expect(requestTo(URL))
        .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

    assertThat(client.unreceivedQuantities()).isEmpty();
  }

  @Test
  @DisplayName("下游 5xx 向上抛出，由 resolver 统一降级（不在此处吞掉）")
  void propagatesDownstreamFailure() {
    server.expect(requestTo(URL)).andRespond(withServerError());

    assertThatThrownBy(client::unreceivedQuantities)
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("响应不是合法 JSON 时抛出，避免返回半份在途数据")
  void malformedJsonIsRejected() {
    server.expect(requestTo(URL))
        .andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));

    assertThatThrownBy(client::unreceivedQuantities)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("解析失败");
  }
}
