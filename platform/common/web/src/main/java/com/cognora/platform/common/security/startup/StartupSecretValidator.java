package com.cognora.platform.common.security.startup;

import com.cognora.platform.common.rest.signature.InternalSignatureSecretResolver;
import com.cognora.platform.common.security.properties.JwtProperties;
import com.cognora.platform.common.security.util.SecretPlaceholders;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 启动守卫：拒绝用「仓库里公开的占位密钥」保护 JWT 与服务间签名通道。
 *
 * <p>长度校验挡不住占位符：{@code platform/common/web} 打包的默认 JWT 密钥有 89 个字符，
 * {@code deploy/k8s/secrets.yml} 与 {@code .env.example} 里的占位值也都超过 64 字符。忘了注入
 * 真实密钥时，服务会照常启动，并用一个写在 git 里的常量签发/校验访问令牌——任何读过仓库的人都能
 * 伪造令牌。本校验器把这种情况变成启动失败。
 *
 * <p>判定规则（{@code cognora.security.secrets.strict} 未显式配置时为 auto）：
 * <ul>
 *   <li><b>生产类 profile</b>（{@code prod}/{@code production}/{@code staging}/{@code uat}/
 *       {@code kubernetes}/{@code k8s}）——任何占位符或长度不足都拒绝启动。<b>注意：没有配置任何
 *       profile 不等于「本地」</b>，请用 {@code cognora.security.secrets.strict=true} 显式收紧。</li>
 *   <li><b>其他 profile（本地开发）</b>——「部署通道占位符」（k8s 清单、{@code .env.example} 的
 *       值，例如 {@code changeit}、{@code scm-jwt-secret-key-2026}、
 *       {@code change-me-to-at-least-64-...}）仍然拒绝启动：这些值不可能是刻意选择的本地密钥；
 *       只有打包在 {@code application.yaml} 里、文档明确说明「仅供本地」的那个默认值允许使用，
 *       并打出 WARN。</li>
 *   <li>{@code strict=true} 强制按生产类处理；{@code strict=false} 只告警，供确实无法注入密钥的
 *       离线环境使用（会在启动日志里留下明确的 WARN）。</li>
 * </ul>
 *
 * <p>JWT 长度本身由 {@code JwtUtils#init()} 兜底；这里重复校验是为了让报错信息直接指向「密钥是
 * 占位符」而不是「长度不足」，避免运维在错误的方向排障。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSecretValidator {

    private static final String STRICT_PROPERTY = "cognora.security.secrets.strict";
    private static final int MIN_JWT_SECRET_BYTES = 64;
    private static final int MIN_INTERNAL_SECRET_LENGTH = 32;

    private static final List<String> PRODUCTION_LIKE_MARKERS = List.of(
            "prod", "production", "staging", "uat", "kubernetes", "k8s");

    private final JwtProperties jwtProperties;
    private final InternalSignatureSecretResolver internalSecretResolver;
    private final Environment environment;

    @PostConstruct
    public void validateSecrets() {
        boolean productionLike = isProductionLike();
        boolean strict = resolveStrict(productionLike);
        log.info("Validating startup secrets (productionLike={}, strict={})", productionLike, strict);

        validateJwtSecret(strict);
        validateInternalSignatureSecret(strict);
    }

    private void validateJwtSecret(boolean strict) {
        String secret = jwtProperties.getSecret();

        if (!StringUtils.hasText(secret)) {
            // JwtUtils#init already refuses a blank secret; keep the message pointed at the config.
            throw new IllegalStateException(
                    "refusing to start: jwt.secret is not configured. "
                            + "Set JWT_SECRET (at least " + MIN_JWT_SECRET_BYTES
                            + " bytes) on every service and on the gateway.");
        }

        boolean shippedDevDefault = SecretPlaceholders.isShippedDevJwtSecret(secret);
        boolean placeholder = SecretPlaceholders.isPlaceholder(secret);
        int length = secret.getBytes(StandardCharsets.UTF_8).length;

        if (strict && placeholder) {
            throw new IllegalStateException(describeJwtPlaceholder(secret, shippedDevDefault));
        }
        if (!strict && placeholder && !shippedDevDefault) {
            // A deployment-lane placeholder is never a deliberate local choice; only the packaged
            // dev default may be used outside a production profile.
            throw new IllegalStateException(describeJwtPlaceholder(secret, false));
        }
        if (strict && length < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException(
                    "refusing to start: jwt.secret is shorter than " + MIN_JWT_SECRET_BYTES
                            + " bytes (HS512 requirement), current length: " + length + ".");
        }
        if (shippedDevDefault) {
            log.warn("jwt.secret is still the development default shipped in "
                    + "platform/common/web's application.yaml. It is public in git - never use it "
                    + "outside a developer machine. Set JWT_SECRET to start with a real key.");
        }
    }

    private void validateInternalSignatureSecret(boolean strict) {
        String secret = internalSecretResolver.resolveSecret();

        if (!StringUtils.hasText(secret)) {
            // A blank internal secret only breaks audit delivery / service-to-service writes; it
            // must not stop a local run that never performs them (the signer already logs an
            // error). In a production-like environment it is a hard failure.
            if (strict) {
                throw new IllegalStateException(
                        "refusing to start: the internal service signature secret is not "
                                + "configured. Set API_SECRET_INTERNAL_SERVICE (or "
                                + "security.signature.app-secrets.internal-service) on every service.");
            }
            log.warn("The internal service signature secret is not configured; every "
                    + "service-to-service write (audit reporting, internal RPC) will be rejected. "
                    + "Set API_SECRET_INTERNAL_SERVICE to the same random value on every service.");
            return;
        }

        if (SecretPlaceholders.isPlaceholder(secret) && strict) {
            throw new IllegalStateException(
                    "refusing to start: the internal service signature secret is still a shipped "
                            + "placeholder. Set API_SECRET_INTERNAL_SERVICE (or "
                            + "security.signature.app-secrets.internal-service) to a random value of "
                            + "at least " + MIN_INTERNAL_SECRET_LENGTH + " characters.");
        }
        if (SecretPlaceholders.isPlaceholder(secret)) {
            log.warn("The internal service signature secret is a shipped placeholder; "
                    + "replace it with a random value before deploying.");
        }
        if (secret.length() < MIN_INTERNAL_SECRET_LENGTH) {
            log.warn("The internal service signature secret is shorter than {} characters; "
                    + "use a random value of at least that length.", MIN_INTERNAL_SECRET_LENGTH);
        }
    }

    private boolean isProductionLike() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            return false;
        }
        return Arrays.stream(profiles)
                .filter(StringUtils::hasText)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> PRODUCTION_LIKE_MARKERS.stream().anyMatch(profile::contains));
    }

    private boolean resolveStrict(boolean productionLike) {
        String configured = environment.getProperty(STRICT_PROPERTY);
        if (StringUtils.hasText(configured)) {
            return Boolean.parseBoolean(configured.trim());
        }
        return productionLike;
    }

    private String describeJwtPlaceholder(String secret, boolean shippedDevDefault) {
        String origin = shippedDevDefault
                ? "the development default packaged in platform/common/web's application.yaml"
                : "a value that ships with this repository (deploy/k8s manifests or .env.example)";
        return "refusing to start: jwt.secret is " + origin + ", which is public in git. "
                + "Set JWT_SECRET to a random value of at least " + MIN_JWT_SECRET_BYTES
                + " bytes; every service and the gateway must use the same value. "
                + "(The configured value is " + secret.length() + " characters long, so a length "
                + "check alone would have accepted it.)";
    }
}
