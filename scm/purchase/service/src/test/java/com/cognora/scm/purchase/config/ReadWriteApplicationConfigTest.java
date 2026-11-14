package com.cognora.scm.purchase.config;

import com.cognora.platform.common.data.rw.config.ReadWriteProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.UrlResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@code application.yml} 中“读写分离已激活”的配置形态。
 *
 * <p>scm-purchase 走 platform-product 的形态：{@code spring.datasource.dynamic.enabled=false}
 * （关掉 baomidou 动态数据源，由 {@code ReadWriteAutoConfiguration} 直接提供 DataSource）
 * 加上 {@code spring.datasource.rw.enabled=true} 与 {@code purchase} 路由 group。
 *
 * <p>本服务原先是 baidou 形态（{@code dynamic.primary: master} +
 * {@code dynamic.datasource.master.*}），迁移后 master 的连接信息下沉到普通的
 * {@code spring.datasource.*} 块，baidou 嵌套块必须彻底消失——否则
 * {@code ReadWriteDatasourceConflictChecker} 会因为 {@code primary=master} 不属于
 * rw group 而让服务启动失败。
 *
 * <p>库名走环境变量 {@code DB_NAME}（默认 {@code db_purchase}），主库、从库与普通
 * 兜底块三处必须使用同一个表达式，否则切换库名时三个数据源会指向不同的库。
 *
 * <p>CI 从不启动 Spring 上下文，因此这个纯解析测试是配置形态唯一的自动化护栏：
 * 形态写错时在测试阶段就失败，而不是等到部署后才发现路由没生效。
 */
@DisplayName("读写分离应用配置测试")
class ReadWriteApplicationConfigTest {

    private static final String APPLICATION_NAME_MARKER = "name: scm-purchase";

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadApplication() {
        try (InputStream in = locateApplicationYaml().openStream()) {
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 application.yml", e);
        }
    }

    /**
     * classpath 上可能存在同名 {@code application.yml}（例如第三方 jar 带的默认配置），
     * 这里按 {@code spring.application.name} 选出本服务的配置文件，避免被同名资源遮蔽。
     */
    private URL locateApplicationYaml() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        Enumeration<URL> urls = classLoader.getResources("application.yml");
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            try (InputStream in = url.openStream()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (content.contains(APPLICATION_NAME_MARKER)) {
                    return url;
                }
            }
        }
        URL fallback = classLoader.getResource("application.yml");
        assertNotNull(fallback, "application.yml 必须在 classpath 上");
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> datasourceSection() {
        Map<String, Object> root = loadApplication();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        assertNotNull(spring, "缺少 spring 节点");
        Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
        assertNotNull(datasource, "缺少 spring.datasource 节点");
        return datasource;
    }

    @Test
    @DisplayName("应关闭 dynamic-datasource 并开启读写分离")
    @SuppressWarnings("unchecked")
    void shouldActivateReadWriteSeparation() {
        Map<String, Object> datasource = datasourceSection();

        Map<String, Object> dynamic = (Map<String, Object>) datasource.get("dynamic");
        assertNotNull(dynamic, "缺少 spring.datasource.dynamic 节点");
        assertEquals(Boolean.FALSE, dynamic.get("enabled"),
                "dynamic-datasource 必须显式关闭，否则会和读写路由抢 DataSource");
        assertFalse(dynamic.containsKey("primary"),
                "baidou 的 dynamic.primary 必须随迁移一起删除：primary=master 不属于 rw group，"
                        + "会让 ReadWriteDatasourceConflictChecker 启动失败");
        assertFalse(dynamic.containsKey("datasource"),
                "baidou 的 dynamic.datasource.master 嵌套块必须随迁移一起删除，"
                        + "master 连接信息已经下沉到普通的 spring.datasource.* 块");

        Map<String, Object> rw = (Map<String, Object>) datasource.get("rw");
        assertNotNull(rw, "缺少 spring.datasource.rw 节点");
        assertEquals(Boolean.TRUE, rw.get("enabled"), "rw 必须开启，否则读写分离没有生效");
        assertEquals("MASTER", rw.get("default-target"), "兜底目标必须是主库");
        assertEquals("2s", rw.get("read-master-after-write"), "写后读窗口必须是 2s");
        assertEquals("1s", rw.get("replication-lag-tolerance"), "复制延迟阈值必须是 1s");
        assertEquals("ROUND_ROBIN", rw.get("load-balance"));
        assertEquals(Boolean.TRUE, rw.get("health-check-enabled"));
        assertEquals("30s", rw.get("health-check-interval"));
        assertEquals(3, rw.get("slave-retry-count"));
        assertEquals(Boolean.TRUE, rw.get("fail-fast-on-slave-connection-failure"));
        assertEquals(Boolean.FALSE, rw.get("slave-down-affects-health"),
                "从库故障不应把整个服务健康状态拉 DOWN");
    }

    @Test
    @DisplayName("熔断参数必须落在 ReadWriteProperties 的取值范围内")
    @SuppressWarnings("unchecked")
    void shouldDeclareCircuitBreaker() {
        Map<String, Object> datasource = datasourceSection();
        Map<String, Object> rw = (Map<String, Object>) datasource.get("rw");
        Map<String, Object> circuitBreaker = (Map<String, Object>) rw.get("circuit-breaker");
        assertNotNull(circuitBreaker, "缺少 spring.datasource.rw.circuit-breaker 节点");
        assertEquals(Boolean.TRUE, circuitBreaker.get("enabled"));
        assertEquals(500, circuitBreaker.get("slow-rt-ms"));
        assertEquals(0.5, circuitBreaker.get("slow-ratio-threshold"));
        assertEquals(0.5, circuitBreaker.get("error-ratio"));
        assertEquals(30, circuitBreaker.get("time-window-seconds"));
        assertEquals(10, circuitBreaker.get("min-request-amount"));
        assertEquals(10000, circuitBreaker.get("stat-interval-ms"));
    }

    @Test
    @DisplayName("purchase group 必须包含主库与一个落回主库的从库")
    @SuppressWarnings("unchecked")
    void shouldDefineMasterAndSlaveGroup() {
        Map<String, Object> datasource = datasourceSection();
        Map<String, Object> rw = (Map<String, Object>) datasource.get("rw");
        Map<String, Object> groups = (Map<String, Object>) rw.get("groups");
        assertNotNull(groups, "缺少 groups 节点");

        Map<String, Object> purchase = (Map<String, Object>) groups.get("purchase");
        assertNotNull(purchase, "缺少 purchase group");

        Map<String, Object> master = (Map<String, Object>) purchase.get("master");
        assertNotNull(master, "purchase group 缺少 master");
        assertTrue(String.valueOf(master.get("url")).contains("db_purchase"));
        assertTrue(master.containsKey("username"));

        List<Map<String, Object>> slaves = (List<Map<String, Object>>) purchase.get("slaves");
        assertNotNull(slaves, "purchase group 缺少 slaves");
        assertEquals(1, slaves.size());
        assertEquals("slave1", slaves.get(0).get("name"));
        assertTrue(String.valueOf(slaves.get(0).get("url")).contains("db_purchase"));
        assertTrue(slaves.get(0).containsKey("username"));
    }

    @Test
    @DisplayName("普通单库兜底配置必须保留")
    @SuppressWarnings("unchecked")
    void shouldKeepPlainDatasourceFallback() {
        Map<String, Object> datasource = datasourceSection();
        String url = String.valueOf(datasource.get("url"));
        assertFalse(url.isBlank(), "rw 关闭时仍需要 spring.datasource.url 兜底");
        assertTrue(url.contains("db_purchase"));
        assertEquals("org.postgresql.Driver", datasource.get("driver-class-name"));
        assertTrue(datasource.containsKey("username"));
        assertTrue(datasource.containsKey("password"));

        Map<String, Object> hikari = (Map<String, Object>) datasource.get("hikari");
        assertNotNull(hikari, "普通兜底块必须保留 hikari 连接池参数");
        assertEquals(20, hikari.get("maximum-pool-size"));
        assertEquals(5, hikari.get("minimum-idle"));
        assertEquals(30000, hikari.get("connection-timeout"));
    }

    /**
     * 直接把 YAML 绑到 {@link ReadWriteProperties}：这是“每个键都真的存在于配置类里”的
     * 唯一自动化证明——未知键在 {@code @ConfigurationProperties} 下只会被静默忽略。
     */
    @Test
    @DisplayName("spring.datasource.rw 必须能完整绑定到 ReadWriteProperties")
    void shouldBindReadWriteProperties() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(
                "application", new UrlResource(locateApplicationYaml()));

        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(source -> environment.getPropertySources().addFirst(source));

        assertEquals("false", environment.getProperty("spring.datasource.dynamic.enabled"),
                "rw 启用时必须关闭 baidou 动态数据源，否则 primary 配置会冲突");

        ReadWriteProperties properties = Binder.get(environment)
                .bind("spring.datasource.rw", ReadWriteProperties.class)
                .orElseThrow(() -> new IllegalStateException(
                        "spring.datasource.rw is not configured in application.yml"));

        assertTrue(properties.isEnabled(), "读写分离必须在 scm-purchase 上启用");
        assertEquals(ReadWriteProperties.RoutingTarget.MASTER, properties.getDefaultTarget(),
                "默认目标必须是主库，避免丢失上下文的线程读到从库");
        assertEquals(2000, properties.getReadMasterAfterWrite().toMillis());
        assertEquals(1000, properties.getReplicationLagTolerance().toMillis());
        assertEquals(ReadWriteProperties.LoadBalanceType.ROUND_ROBIN, properties.getLoadBalance());
        assertTrue(properties.isHealthCheckEnabled());
        assertEquals(30000, properties.getHealthCheckInterval().toMillis());
        assertEquals(3, properties.getSlaveRetryCount());
        assertTrue(properties.isFailFastOnSlaveConnectionFailure());
        assertFalse(properties.isSlaveDownAffectsHealth());
        assertTrue(properties.getCircuitBreaker().isEnabled());
        assertEquals(500, properties.getCircuitBreaker().getSlowRtMs());
        assertEquals(0.5, properties.getCircuitBreaker().getSlowRatioThreshold());
        assertEquals(0.5, properties.getCircuitBreaker().getErrorRatio());
        assertEquals(30, properties.getCircuitBreaker().getTimeWindowSeconds());
        assertEquals(10, properties.getCircuitBreaker().getMinRequestAmount());
        assertEquals(10000, properties.getCircuitBreaker().getStatIntervalMs());

        ReadWriteProperties.DataSourceGroup group = properties.getGroups().get("purchase");
        assertNotNull(group, "group 名必须与配置一致");
        assertNotNull(group.getMaster());
        assertTrue(group.getMaster().getUrl().contains("db_purchase"));
        assertNotNull(group.getMaster().getUsername());
        assertEquals(20, group.getMaster().getMaximumPoolSize());
        assertEquals(5, group.getMaster().getMinimumIdle());
        assertEquals(30000, group.getMaster().getConnectionTimeout().toMillis());

        assertEquals(1, group.getSlaves().size());
        ReadWriteProperties.SlaveDataSourceConfig slave = group.getSlaves().getFirst();
        assertEquals("slave1", slave.getName());
        assertTrue(slave.getUrl().contains("db_purchase"));
        assertNotNull(slave.getUsername());
        assertEquals(5000, slave.getConnectionTimeout().toMillis(),
                "从库连接探测必须快速失败");
        assertEquals(1, slave.getWeight());
        assertTrue(slave.isAvailable());
        assertEquals(-1, slave.getInitializationFailTimeout(),
                "从库不可达时不能阻止应用启动");
    }
}
