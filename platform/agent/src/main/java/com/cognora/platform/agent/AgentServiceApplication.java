package com.cognora.platform.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {
        "com.cognora.platform.agent",
        "com.cognora.platform.common.security",
        "com.cognora.platform.common.tenant",
        "com.cognora.platform.common.log",
        "com.cognora.platform.common.rest",
        "com.cognora.platform.common.response",
        "com.cognora.platform.common.trace",
        "com.cognora.platform.common.feature",
        "com.cognora.platform.common.web"
})
@EnableDiscoveryClient
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
