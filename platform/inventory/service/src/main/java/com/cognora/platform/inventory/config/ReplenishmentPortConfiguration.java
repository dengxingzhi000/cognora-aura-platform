package com.cognora.platform.inventory.config;

import com.cognora.platform.common.rest.interceptor.RestClientRequestSignatureInterceptor;
import com.cognora.platform.inventory.service.command.SkuMaterialMappingResolver;
import com.cognora.platform.inventory.service.replenishment.HttpPurchaseInTransitClient;
import com.cognora.platform.inventory.service.replenishment.ProductSkuMaterialMappingResolver;
import com.cognora.platform.inventory.service.replenishment.PurchaseInTransitPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 补货链路的跨服务端口装配。
 *
 * <p>两个端口：
 * <ul>
 *   <li>{@link PurchaseInTransitPort}：采购在途量，经 Nacos 调 scm-purchase 的只读端点。
 *       默认启用；{@code cognora.inventory.replenishment.purchase-in-transit.enabled=false}
 *       可退回 {@code Empty}（离线开发/单测）；</li>
 *   <li>{@link SkuMaterialMappingResolver}：SKU→material，仍待接商品主数据。</li>
 * </ul>
 *
 * <p><strong>为什么给 RestClient 单独设超时：</strong>补货计算是批处理，
 * 每轮要扫全租户台账。若采购服务卡住而客户端用默认无限等待，一次计算会被
 * 拖到分钟级，XXL-Job 会堆叠。这里把连接/读取超时压到秒级，
 * 超时后由 {@code InTransitQuantityResolver} 降级为「仅建议在途」。
 *
 * <p><strong>为什么这几个 Bean 放在 {@code @Configuration} 的 {@code @Bean} 方法上：</strong>
 * AGENTS 记录的坑——{@code @ConditionalOnMissingBean} 标在组件扫描的
 * {@code @Component} 上会自排除（条件判断时正在注册自己）。{@code @Bean} 方法
 * 在配置类处理阶段求值，语义才正确。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ReplenishmentPortConfiguration {

  /**
   * 采购在途客户端专用的负载均衡 RestClient。
   *
   * <p>不复用 {@code common/web} 的 {@code restClientBuilder}：那个 builder 会挂
   * 租户/令牌透传，语义是「代表当前用户调用」。补货定时任务没有用户令牌，
   * 职责也不同，因此独立构造——但<strong>必须</strong>挂上服务间签名拦截器：
   * <code>/api/v1/pur-order/in-transit</code> 登记在
   * {@code internal-signature-paths} 里，不带 HMAC 签名会被
   * {@code InternalEndpointSignatureFilter} 判 403。
   */
  @Bean(name = "purchaseInTransitRestClient")
  @LoadBalanced
  public RestClient purchaseInTransitRestClient(
      RestClientRequestSignatureInterceptor signatureInterceptor,
      @Value("${cognora.inventory.replenishment.purchase-in-transit.connect-timeout-ms:2000}")
      long connectTimeoutMs,
      @Value("${cognora.inventory.replenishment.purchase-in-transit.read-timeout-ms:5000}")
      long readTimeoutMs,
      @Value("${cognora.inventory.replenishment.purchase-in-transit.service-id:scm-purchase}")
      String serviceId) {

    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Math.min(connectTimeoutMs, Integer.MAX_VALUE));
    factory.setReadTimeout((int) Math.min(readTimeoutMs, Integer.MAX_VALUE));

    log.info("补货在途客户端: serviceId={}, connectTimeout={}ms, readTimeout={}ms",
        serviceId, connectTimeoutMs, readTimeoutMs);

    return RestClient.builder()
        .baseUrl("http://" + serviceId)
        .requestFactory(factory)
        .requestInterceptor(signatureInterceptor)
        // 下游要按租户过滤在途量：补货计算在 executeInTenantContext 内运行，
        // 这里把当前租户头透传出去，采购侧 TenantFilter 才能建立上下文。
        .requestInterceptor((request, body, execution) -> {
          java.util.UUID tenantId =
              com.cognora.platform.common.tenant.TenantContextHolder.getTenantId();
          if (tenantId != null) {
            request.getHeaders().set("X-Tenant-Id", tenantId.toString());
          }
          return execution.execute(request, body);
        })
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(PurchaseInTransitPort.class)
  @ConditionalOnProperty(
      prefix = "cognora.inventory.replenishment.purchase-in-transit",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public PurchaseInTransitPort purchaseInTransitPort(
      RestClient purchaseInTransitRestClient, ObjectMapper objectMapper) {
    return new HttpPurchaseInTransitClient(purchaseInTransitRestClient, objectMapper);
  }

  /** 关闭开关时的退化实现：只当作「没有已知采购在途」。 */
  @Bean
  @ConditionalOnMissingBean(PurchaseInTransitPort.class)
  public PurchaseInTransitPort emptyPurchaseInTransitPort() {
    log.warn("采购在途查询已关闭（cognora.inventory.replenishment.purchase-in-transit.enabled=false），"
        + "补货计算只按建议在途去重，可能重复建议");
    return new PurchaseInTransitPort.Empty();
  }

  /** 商品主数据客户端：只读查 prod_sku，把 SKU 转成 material 口径。 */
  @Bean(name = "productLookupRestClient")
  @LoadBalanced
  public RestClient productLookupRestClient(
      RestClientRequestSignatureInterceptor signatureInterceptor,
      @Value("${cognora.inventory.replenishment.product-lookup.connect-timeout-ms:2000}")
      long connectTimeoutMs,
      @Value("${cognora.inventory.replenishment.product-lookup.read-timeout-ms:3000}")
      long readTimeoutMs,
      @Value("${cognora.inventory.replenishment.product-lookup.service-id:platform-product}")
      String serviceId) {

    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Math.min(connectTimeoutMs, Integer.MAX_VALUE));
    factory.setReadTimeout((int) Math.min(readTimeoutMs, Integer.MAX_VALUE));

    return RestClient.builder()
        .baseUrl("http://" + serviceId)
        .requestFactory(factory)
        .requestInterceptor(signatureInterceptor)
        .requestInterceptor((request, body, execution) -> {
          java.util.UUID tenantId =
              com.cognora.platform.common.tenant.TenantContextHolder.getTenantId();
          if (tenantId != null) {
            request.getHeaders().set("X-Tenant-Id", tenantId.toString());
          }
          return execution.execute(request, body);
        })
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(SkuMaterialMappingResolver.class)
  @ConditionalOnProperty(
      prefix = "cognora.inventory.replenishment.product-lookup",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public SkuMaterialMappingResolver skuMaterialMappingResolver(
      RestClient productLookupRestClient, ObjectMapper objectMapper) {
    return new ProductSkuMaterialMappingResolver(productLookupRestClient, objectMapper);
  }

  /** 关闭商品主数据查询时的退化实现：convert 会明确报「待补映射」。 */
  @Bean
  @ConditionalOnMissingBean(SkuMaterialMappingResolver.class)
  public SkuMaterialMappingResolver noneSkuMaterialMappingResolver() {
    log.warn("商品主数据查询已关闭（cognora.inventory.replenishment.product-lookup.enabled=false），"
        + "补货建议转采购计划会因缺少 SKU→material 映射而失败");
    return new SkuMaterialMappingResolver.None();
  }
}
