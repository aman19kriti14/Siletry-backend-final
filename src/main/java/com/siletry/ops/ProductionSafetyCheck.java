package com.siletry.ops;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start in production with unsafe settings: a public JWT secret would let anyone
 * forge a login, and dev tools would show login codes on screen.
 *
 * Production = SILETRY_ENV=production, Railway's environment named "production", or running on Render
 * (unless SILETRY_ENV says otherwise). If a deploy fails here, the host keeps the previous deployment running.
 */
@Component
public class ProductionSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    private final String env;
    private final String jwtSecret;
    private final String credentialsKey;
    private final boolean devTools;
    private final boolean seedDemo;
    private final String ipSalt;
    private final String verifyToken;
    private final String allowedOrigins;

    public ProductionSafetyCheck(@Value("${siletry.env}") String env,
                                 @Value("${siletry.on-render:false}") boolean onRender,
                                 @Value("${siletry.jwt.secret}") String jwtSecret,
                                 @Value("${siletry.credentials-key}") String credentialsKey,
                                 @Value("${siletry.dev-tools}") boolean devTools,
                                 @Value("${siletry.seed-demo}") boolean seedDemo,
                                 @Value("${siletry.ops.leads.ip-hash-salt}") String ipSalt,
                                 @Value("${siletry.meta.verify-token}") String verifyToken,
                                 @Value("${siletry.cors.allowed-origins}") String allowedOrigins) {
        // Render: production unless SILETRY_ENV was set to something explicit
        this.env = onRender && (env == null || env.isBlank() || "local".equals(env)) ? "production" : env;
        this.jwtSecret = jwtSecret;
        this.credentialsKey = credentialsKey;
        this.devTools = devTools;
        this.seedDemo = seedDemo;
        this.ipSalt = ipSalt;
        this.verifyToken = verifyToken;
        this.allowedOrigins = allowedOrigins;
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(env == null ? "" : env.trim());
    }

    @PostConstruct
    void check() {
        if (!isProduction()) {
            log.info("Environment '{}': production safety checks skipped", env);
            return;
        }
        List<String> errors = new ArrayList<>();
        if (jwtSecret == null || jwtSecret.length() < 32 || jwtSecret.startsWith("change-me"))
            errors.add("JWT_SECRET is missing or the dev default (set 32+ random characters)");
        if (credentialsKey == null || credentialsKey.length() < 24 || credentialsKey.startsWith("dev-only"))
            errors.add("CREDENTIALS_KEY is missing or the dev default (set once to a long random string)");
        if (devTools)
            errors.add("DEV_TOOLS is true (shows login codes on screen and opens the simulator)");

        List<String> warnings = new ArrayList<>();
        if (seedDemo) warnings.add("SEED_DEMO is true (set it to false)");
        if (ipSalt == null || ipSalt.startsWith("change-me")) warnings.add("LEADS_IP_SALT is the default");
        if (verifyToken == null || verifyToken.isBlank()) warnings.add("META_VERIFY_TOKEN is empty (Meta webhooks can't be verified)");
        if (allowedOrigins.contains("localhost")) warnings.add("ALLOWED_ORIGINS still includes localhost");

        warnings.forEach(w -> log.warn("Production config: {}", w));
        if (!errors.isEmpty()) {
            errors.forEach(e -> log.error("Production config: {}", e));
            throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", errors));
        }
        log.info("Production safety checks passed");
    }
}
