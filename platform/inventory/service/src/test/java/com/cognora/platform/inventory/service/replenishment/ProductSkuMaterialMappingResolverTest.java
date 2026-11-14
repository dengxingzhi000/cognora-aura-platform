package com.cognora.platform.inventory.service.replenishment;

import com.cognora.platform.inventory.service.command.SkuMaterialMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * SKU→material 映射用例：成功映射、缺字段、下游失败。
 *
 * <p>重点断言「拿不到就返回 null」，绝不回退成用 skuId 冒充 materialCode。
 */
class ProductSkuMaterialMappingResolverTest {

  private static final String BASE = "http://platform-product";

  private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
  private final MockRestServiceServer server =
      MockRestServiceServer.bindTo(builder).build();
  private final ProductSkuMaterialMappingResolver resolver =
      new ProductSkuMaterialMappingResolver(builder.build(), new ObjectMapper());

  @Test
  @DisplayName("skuCode 作为 materialCode，skuName 作为 materialName，单位缺省为件")
  void mapsSkuToMaterial() {
    server.expect(requestTo(BASE + "/api/v1/prod-sku/sku-1"))
        .andRespond(withSuccess("""
            {"code":200,"data":{
              "id":"sku-1","skuCode":"SKU-IP15P-128","skuName":"iPhone 15 Pro 128G"
            }}
            """, MediaType.APPLICATION_JSON));

    SkuMaterialMapping mapping = resolver.resolve("sku-1");

    assertThat(mapping).isNotNull();
    assertThat(mapping.materialCode()).isEqualTo("SKU-IP15P-128");
    assertThat(mapping.materialName()).isEqualTo("iPhone 15 Pro 128G");
    assertThat(mapping.unit()).isEqualTo("件");
  }

  @Test
  @DisplayName("skuName 缺失时用 skuCode 兜底，满足 material_name NOT NULL")
  void fallsBackToSkuCodeWhenNameMissing() {
    server.expect(requestTo(BASE + "/api/v1/prod-sku/sku-1"))
        .andRespond(withSuccess("""
            {"code":200,"data":{"skuCode":"SKU-X"}}
            """, MediaType.APPLICATION_JSON));

    SkuMaterialMapping mapping = resolver.resolve("sku-1");

    assertThat(mapping).isNotNull();
    assertThat(mapping.materialName()).isEqualTo("SKU-X");
  }

  @Test
  @DisplayName("缺 skuCode 时返回 null —— 绝不用 skuId 冒充 materialCode")
  void missingSkuCodeReturnsNull() {
    server.expect(requestTo(BASE + "/api/v1/prod-sku/sku-1"))
        .andRespond(withSuccess("""
            {"code":200,"data":{"id":"sku-1","skuName":"无编码商品"}}
            """, MediaType.APPLICATION_JSON));

    assertThat(resolver.resolve("sku-1")).isNull();
  }

  @Test
  @DisplayName("商品不存在（404）返回 null，不抛错阻断整张建议单")
  void notFoundReturnsNull() {
    server.expect(requestTo(BASE + "/api/v1/prod-sku/sku-x"))
        .andRespond(withStatus(NOT_FOUND));

    assertThat(resolver.resolve("sku-x")).isNull();
  }

  @Test
  @DisplayName("下游 5xx 返回 null")
  void downstreamFailureReturnsNull() {
    server.expect(requestTo(BASE + "/api/v1/prod-sku/sku-1"))
        .andRespond(withServerError());

    assertThat(resolver.resolve("sku-1")).isNull();
  }

  @Test
  @DisplayName("data 为空或不是对象时返回 null")
  void emptyDataReturnsNull() {
    server.expect(requestTo(BASE + "/api/v1/prod-sku/sku-1"))
        .andRespond(withSuccess("{\"code\":404,\"message\":\"not found\"}",
            MediaType.APPLICATION_JSON));

    assertThat(resolver.resolve("sku-1")).isNull();
  }

  @Test
  @DisplayName("空白 skuId 直接返回 null，不发请求")
  void blankSkuIdDoesNotCallRemote() {
    assertThat(resolver.resolve(null)).isNull();
    assertThat(resolver.resolve("  ")).isNull();
    // MockRestServiceServer 未设期望，若有请求会被判为意外调用
    server.verify();
  }
}
