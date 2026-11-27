package com.cognora.platform.agent;

import com.cognora.platform.agent.client.AgentRuntimeClient;
import com.cognora.platform.agent.controller.AgentController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceApplicationTest {

    @Test
    void bootsWithIsolatedH2AndWithoutServiceDiscovery() {
        SpringApplication application = new SpringApplication(AgentServiceApplication.class);
        try (ConfigurableApplicationContext context = application.run(
                "--server.port=0",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "--security.signature.app-secrets.internal-service=0123456789abcdef0123456789abcdef",
                "--cognora.agent.enterprise-base-url=http://127.0.0.1:18762",
                "--cognora.agent.browser-base-url=http://127.0.0.1:14320",
                "--cognora.agent.task-center.enabled=false",
                "--spring.datasource.url=jdbc:h2:mem:agent;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.flyway.enabled=false")) {
            assertNotNull(context.getBean(AgentRuntimeClient.class));
            assertNotNull(context.getBean(AgentController.class));
            assertTrue(context.isRunning());
        }
    }
}
