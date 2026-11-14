package com.cognora.platform.common.security.startup;

import com.cognora.platform.common.rest.signature.InternalSignatureSecretResolver;
import com.cognora.platform.common.security.properties.JwtProperties;
import com.cognora.platform.common.security.util.SecretPlaceholders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动守卫用例：占位密钥必须在生产类环境里拒绝启动，本地默认值允许但告警。
 */
@DisplayName("Startup secret guard")
class StartupSecretValidatorTest {

    private static final String REAL_JWT_SECRET =
            "5f4dcc3b5aa765d61d8327deb882cf995f4dcc3b5aa765d61d8327deb882cf99";
    private static final String REAL_INTERNAL_SECRET =
            "3c9909afec25354d551dae21590bb26e38d53f2173b8d3dc3eee4c047e7ab1c1eb8b85103e3be7ba613b31bb5c9c36214dc9f14a42fd7a2fdb84856bca5c44c2";

    @Test
    @DisplayName("The shipped dev default blocks startup under a production profile")
    void shippedDevDefaultBlocksProductionStartup() {
        MockEnvironment environment = productionEnvironment("prod");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> validator(environment, SecretPlaceholders.SHIPPED_DEV_JWT_SECRET,
                        REAL_INTERNAL_SECRET).validateSecrets());

        assertTrue(failure.getMessage().contains("jwt.secret"));
        assertTrue(failure.getMessage().contains("public in git"));
    }

    @Test
    @DisplayName("The Kubernetes profile counts as production-like")
    void kubernetesProfileIsProductionLike() {
        MockEnvironment environment = productionEnvironment("kubernetes");

        assertThrows(IllegalStateException.class,
                () -> validator(environment, SecretPlaceholders.SHIPPED_DEV_JWT_SECRET,
                        REAL_INTERNAL_SECRET).validateSecrets());
    }

    @Test
    @DisplayName("A deployment-lane placeholder (k8s manifest / .env.example) blocks startup")
    void deploymentPlaceholderBlocksStartup() {
        MockEnvironment environment = new MockEnvironment();

        assertThrows(IllegalStateException.class,
                () -> validator(environment, "change-me-to-at-least-64-characters-long-random-string-0000000000",
                        REAL_INTERNAL_SECRET).validateSecrets());
    }

    @Test
    @DisplayName("The shipped dev default is accepted (with a warning) in a plain local run")
    void shippedDevDefaultIsAllowedLocally() {
        MockEnvironment environment = new MockEnvironment();

        assertDoesNotThrow(() -> validator(environment, SecretPlaceholders.SHIPPED_DEV_JWT_SECRET,
                REAL_INTERNAL_SECRET).validateSecrets());
    }

    @Test
    @DisplayName("A real secret passes everywhere")
    void realSecretPasses() {
        assertDoesNotThrow(() -> validator(productionEnvironment("prod"), REAL_JWT_SECRET,
                REAL_INTERNAL_SECRET).validateSecrets());
    }

    @Test
    @DisplayName("A short secret blocks startup under a production profile")
    void shortSecretBlocksProductionStartup() {
        MockEnvironment environment = productionEnvironment("production");

        assertThrows(IllegalStateException.class,
                () -> validator(environment, "short-secret", REAL_INTERNAL_SECRET).validateSecrets());
    }

    @Test
    @DisplayName("An explicit strict flag tightens a plain local run")
    void explicitStrictFlagTightensLocalRun() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("cognora.security.secrets.strict", "true");

        assertThrows(IllegalStateException.class,
                () -> validator(environment, SecretPlaceholders.SHIPPED_DEV_JWT_SECRET,
                        REAL_INTERNAL_SECRET).validateSecrets());
    }

    @Test
    @DisplayName("An explicit strict=false keeps a production-like environment bootable")
    void explicitNonStrictOverridesProfile() {
        MockEnvironment environment = productionEnvironment("prod");
        environment.setProperty("cognora.security.secrets.strict", "false");

        assertDoesNotThrow(() -> validator(environment, SecretPlaceholders.SHIPPED_DEV_JWT_SECRET,
                REAL_INTERNAL_SECRET).validateSecrets());
    }

    @Test
    @DisplayName("A placeholder internal signature secret blocks a production start")
    void placeholderInternalSecretBlocksProductionStartup() {
        MockEnvironment environment = productionEnvironment("prod");

        assertThrows(IllegalStateException.class,
                () -> validator(environment, REAL_JWT_SECRET, "your-internal-secret-key").validateSecrets());
    }

    @Test
    @DisplayName("A missing internal signature secret only warns outside production")
    void missingInternalSecretIsLenientLocally() {
        MockEnvironment environment = new MockEnvironment();

        assertDoesNotThrow(() -> validator(environment, SecretPlaceholders.SHIPPED_DEV_JWT_SECRET, "")
                .validateSecrets());
    }

    @Test
    @DisplayName("A missing internal signature secret blocks a production start")
    void missingInternalSecretBlocksProductionStartup() {
        MockEnvironment environment = productionEnvironment("prod");

        assertThrows(IllegalStateException.class,
                () -> validator(environment, REAL_JWT_SECRET, "").validateSecrets());
    }

    private static MockEnvironment productionEnvironment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private static StartupSecretValidator validator(MockEnvironment environment, String jwtSecret,
                                                    String internalSecret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(jwtSecret);
        environment.setProperty("security.signature.app-secrets.internal-service", internalSecret);
        return new StartupSecretValidator(properties,
                new InternalSignatureSecretResolver(environment), environment);
    }
}
