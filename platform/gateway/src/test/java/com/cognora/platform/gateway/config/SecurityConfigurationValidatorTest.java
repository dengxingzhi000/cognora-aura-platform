package com.cognora.platform.gateway.config;

import com.cognora.platform.gateway.properties.ApiSignatureProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SecurityConfigurationValidator.
 * Verifies security configuration validation logic including:
 * - Missing configuration detection
 * - Secret strength validation
 * - Clock skew configuration validation
 * - Production vs development mode behavior
 */
@ExtendWith(MockitoExtension.class)
class SecurityConfigurationValidatorTest {

    @Mock
    private ApiSignatureProperties signatureProperties;

    private SecurityConfigurationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SecurityConfigurationValidator(signatureProperties);

        // Set default values for all required fields
        ReflectionTestUtils.setField(validator, "webAppSecret", "test-web-app-secret-32-chars-long");
        ReflectionTestUtils.setField(validator, "internalServiceSecret", "test-internal-service-secret-32-chars");
        ReflectionTestUtils.setField(validator, "identitySignatureSecret", "test-identity-signature-secret-64-chars-long-for-hmac-sha256-algorithm");
        // The gateway verifies every access token with this key, so the validator now rejects a
        // placeholder or a sub-512-bit value outside development. Tests that are about a different
        // check must therefore start from a usable secret.
        ReflectionTestUtils.setField(validator, "jwtSecret",
                "5f4dcc3b5aa765d61d8327deb882cf995f4dcc3b5aa765d61d8327deb882cf99");
        ReflectionTestUtils.setField(validator, "activeProfile", "dev");
    }

    @Test
    void placeholderJwtSecret_ProductionMode_ShouldFail() {
        ReflectionTestUtils.setField(validator, "activeProfile", "production");
        ReflectionTestUtils.setField(validator, "jwtSecret",
                "CHANGE-ME-dev-only-secret-key-0123456789abcdef0123456789abcdef0123456789abcdef0123456789ab");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> validator.validateSecurityConfiguration());
        assertTrue(failure.getMessage().contains("jwt.secret"));
    }

    @Test
    void placeholderJwtSecret_KubernetesProfile_ShouldFail() {
        // The k8s manifests set SPRING_PROFILES_ACTIVE=kubernetes; that must count as production.
        ReflectionTestUtils.setField(validator, "activeProfile", "kubernetes");
        ReflectionTestUtils.setField(validator, "jwtSecret",
                "scm-jwt-secret-key-2026");

        assertThrows(IllegalStateException.class, () -> validator.validateSecurityConfiguration());
    }

    @Test
    void placeholderJwtSecret_DevMode_ShouldOnlyWarn() {
        ReflectionTestUtils.setField(validator, "activeProfile", "dev");
        ReflectionTestUtils.setField(validator, "jwtSecret", "changeit");

        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(5));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));

        assertDoesNotThrow(() -> validator.validateSecurityConfiguration());
    }

    @Test
    void testValidConfiguration_DevMode_ShouldPass() {
        // Given: Valid configuration in dev mode
        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(5));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));

        // When & Then: Should not throw exception
        assertDoesNotThrow(() -> validator.validateSecurityConfiguration());
    }

    @Test
    void testMissingConfiguration_DevMode_ShouldWarn() {
        // Given: Missing configuration in dev mode
        ReflectionTestUtils.setField(validator, "webAppSecret", "");
        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(5));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));

        // When & Then: Should not throw exception (only warn)
        assertDoesNotThrow(() -> validator.validateSecurityConfiguration());
    }

    @Test
    void testMissingConfiguration_ProductionMode_ShouldFail() {
        // Given: Missing configuration in production mode
        ReflectionTestUtils.setField(validator, "webAppSecret", "");
        ReflectionTestUtils.setField(validator, "activeProfile", "production");

        // When & Then: Should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> validator.validateSecurityConfiguration());
    }

    @Test
    void testWeakSecret_ProductionMode_ShouldWarn() {
        // Given: Weak secret in production mode (too short)
        ReflectionTestUtils.setField(validator, "webAppSecret", "short");
        ReflectionTestUtils.setField(validator, "activeProfile", "production");
        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(5));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));

        // When & Then: Should not throw exception but log warning
        assertDoesNotThrow(() -> validator.validateSecurityConfiguration());
    }

    @Test
    void testClockSkewValidation_InvalidConfiguration_DevMode_ShouldWarn() {
        // Given: nonceTtl < allowedClockSkew in dev mode
        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(3));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));
        ReflectionTestUtils.setField(validator, "activeProfile", "dev");

        // When & Then: Should not throw exception (only warn)
        assertDoesNotThrow(() -> validator.validateSecurityConfiguration());
    }

    @Test
    void testClockSkewValidation_InvalidConfiguration_ProductionMode_ShouldFail() {
        // Given: nonceTtl < allowedClockSkew in production mode
        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(3));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));
        ReflectionTestUtils.setField(validator, "activeProfile", "production");

        // When & Then: Should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> validator.validateSecurityConfiguration());
    }

    @Test
    void testClockSkewValidation_ValidConfiguration_ShouldPass() {
        // Given: nonceTtl >= allowedClockSkew
        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(5));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));

        // When & Then: Should not throw exception
        assertDoesNotThrow(() -> validator.validateSecurityConfiguration());
    }

    @Test
    void testClockSkewValidation_NonceTtlGreaterThanSkew_ShouldPass() {
        // Given: nonceTtl > allowedClockSkew (recommended configuration)
        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(10));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));

        // When & Then: Should not throw exception
        assertDoesNotThrow(() -> validator.validateSecurityConfiguration());
    }

    @Test
    void testProductionLikeEnvironments_ShouldBeDetected() {
        // Test various production-like profile names
        String[] productionProfiles = {"prod", "production", "staging", "uat"};

        for (String profile : productionProfiles) {
            ReflectionTestUtils.setField(validator, "activeProfile", profile);
            ReflectionTestUtils.setField(validator, "webAppSecret", "");

            // Should throw exception for production-like environments
            assertThrows(IllegalStateException.class,
                () -> validator.validateSecurityConfiguration(),
                "Profile '" + profile + "' should be treated as production-like");
        }
    }

    @Test
    void testAllSecretsPresent_ProductionMode_ShouldValidateStrength() {
        // Given: All secrets present in production mode
        ReflectionTestUtils.setField(validator, "activeProfile", "production");
        org.mockito.Mockito.when(signatureProperties.getNonceTtl()).thenReturn(Duration.ofMinutes(5));
        org.mockito.Mockito.when(signatureProperties.getAllowedClockSkew()).thenReturn(Duration.ofMinutes(5));

        // When & Then: Should not throw exception
        assertDoesNotThrow(() -> validator.validateSecurityConfiguration());
    }

    @Test
    void testMultipleMissingConfigurations_ShouldReportAll() {
        // Given: Multiple missing configurations
        ReflectionTestUtils.setField(validator, "webAppSecret", "");
        ReflectionTestUtils.setField(validator, "internalServiceSecret", "");
        ReflectionTestUtils.setField(validator, "identitySignatureSecret", "");
        ReflectionTestUtils.setField(validator, "activeProfile", "production");

        // When & Then: Should throw exception with all missing configs
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator.validateSecurityConfiguration());

        assertTrue(exception.getMessage().contains("Missing 3 required security configuration(s)"));
    }
}