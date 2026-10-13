package com.siletry.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Settings for Siletry's own operations: alerts to the team and the ops API.
 * Alerts are sent from Siletry's own WhatsApp number (META_ADMIN_*), the same one used
 * for login codes and doctor alerts, so there are no extra Meta credentials here.
 */
@ConfigurationProperties(prefix = "siletry.ops")
public record OpsProperties(
        String apiKey,
        String alertTo,
        String alertMode,
        Leads leads
) {
    public OpsProperties {
        leads = leads == null ? new Leads(null, 0, null, null, 0, null) : leads;
    }

    /**
     * "text" (default): a normal WhatsApp message. Arrives only if the recipient messaged Siletry's number
     * in the last 24 hours (Meta accepts it either way and reports a late failure through the webhook).
     * "template": the approved siletry_staff_alert template. Arrives any time.
     */
    public boolean useTemplate() {
        return "template".equalsIgnoreCase(alertMode == null ? "" : alertMode.trim());
    }

    public boolean apiEnabled() {
        return apiKey != null && apiKey.length() >= 24;
    }

    /** Who gets team alerts. Comma-separated in config, normalized to digits with country code. */
    public List<String> alertRecipients() {
        if (alertTo == null || alertTo.isBlank()) return List.of();
        return Arrays.stream(alertTo.split(","))
                .map(s -> s.replaceAll("[^0-9]", ""))
                .map(s -> s.length() == 10 ? "91" + s : s)
                .filter(s -> s.length() >= 11)
                .distinct()
                .toList();
    }

    public record Leads(
            String ipHashSalt,
            int rateLimitMax,
            Duration rateLimitWindow,
            Duration duplicateWindow,
            int maxNotifyAttempts,
            Duration retryBaseDelay
    ) {
        public Leads {
            ipHashSalt = ipHashSalt != null && !ipHashSalt.isBlank() ? ipHashSalt : "siletry-leads";
            rateLimitMax = rateLimitMax > 0 ? rateLimitMax : 5;
            rateLimitWindow = rateLimitWindow != null ? rateLimitWindow : Duration.ofMinutes(10);
            duplicateWindow = duplicateWindow != null ? duplicateWindow : Duration.ofHours(24);
            maxNotifyAttempts = maxNotifyAttempts > 0 ? maxNotifyAttempts : 6;
            retryBaseDelay = retryBaseDelay != null ? retryBaseDelay : Duration.ofMinutes(1);
        }
    }
}
