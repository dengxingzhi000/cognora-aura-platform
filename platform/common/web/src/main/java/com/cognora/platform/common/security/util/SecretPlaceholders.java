package com.cognora.platform.common.security.util;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Recognises the secret values that ship with this repository.
 *
 * <p>Every deployment lane has a documented placeholder: {@code deploy/k8s/secrets.yml} and
 * {@code deploy/k8s/platform-auth.yaml} carry {@code CHANGE-ME-}/{@code scm-jwt-secret-key-2026},
 * {@code platform/common/web}'s {@code application.yaml} ships a {@code CHANGE-ME-dev-only-...}
 * default, and {@code .env.example} ships {@code change-me-to-at-least-64-characters-...}. A
 * placeholder that is long enough to satisfy the length checks boots happily and signs real
 * tokens with a value anyone can read in git, so length is not a security check on its own.
 *
 * <p>The JWT startup guards (this module's {@code StartupSecretValidator} and the gateway's
 * {@code SecurityConfigurationValidator}) read this one list so a new placeholder cannot be added
 * in one place and forgotten in another. The audit service keeps its own stricter, unconditional
 * check for the audit write channel (see {@code AuditWriteSecurityValidator}).
 */
public final class SecretPlaceholders {

    /** Values (or fragments, case-insensitive) that must never protect a production channel. */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change-me",
            "change_me",
            "changeme",
            "changeit",
            "your-secret",
            "your-internal",
            "placeholder",
            "dev-only",
            "dev_only",
            // deploy/k8s/platform-auth.yaml ships this literal as a working-looking secret.
            "scm-jwt-secret-key-2026");

    /** The default JWT secret in {@code platform/common/web/src/main/resources/application.yaml}. */
    public static final String SHIPPED_DEV_JWT_SECRET =
            "CHANGE-ME-dev-only-secret-key-0123456789abcdef0123456789abcdef0123456789abcdef0123456789ab";

    private SecretPlaceholders() {
    }

    /**
     * Whether a value is a placeholder rather than an operator-supplied secret.
     *
     * <p>A blank value is not a placeholder: "not configured" and "configured with a public
     * constant" are different failures and are reported differently.
     */
    public static boolean isPlaceholder(String secret) {
        if (!StringUtils.hasText(secret)) {
            return false;
        }
        if (isShippedDevJwtSecret(secret)) {
            return true;
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
    }

    /**
     * Whether the value is exactly the JWT default packaged in {@code platform/common/web}'s
     * {@code application.yaml}.
     *
     * <p>This one is treated differently from the other placeholders: it is the documented local
     * development default, so it may be used on a laptop - but never where a real deployment is
     * detected, because the value is public in git.
     */
    public static boolean isShippedDevJwtSecret(String secret) {
        return StringUtils.hasText(secret) && SHIPPED_DEV_JWT_SECRET.equals(secret.trim());
    }
}
