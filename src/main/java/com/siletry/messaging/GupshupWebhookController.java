package com.siletry.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.messaging.gateway.GatewayRouter;
import com.siletry.messaging.gateway.MetaCloudApiGateway;
import com.siletry.messaging.gateway.WhatsAppGateway.InboundMessage;
import com.siletry.messaging.gateway.WhatsAppGateway.WebhookBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Webhook for apps on Gupshup (v3 subscription = Meta's webhook format plus "gs_app_id").
 * Gupshup doesn't sign webhooks, so the URL carries a secret:
 *   https://<backend>/api/webhooks/gupshup/<GUPSHUP_WEBHOOK_SECRET>
 * The clinic is found by gs_app_id (falls back to Meta's phone_number_id).
 */
@RestController
@RequestMapping("/api/webhooks/gupshup")
public class GupshupWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GupshupWebhookController.class);

    private final ObjectMapper mapper;
    private final InboundMessageService inbound;
    private final AdminCommandService admin;
    private final ClinicRepository clinics;
    private final GatewayRouter router;
    private final String secret;

    public GupshupWebhookController(ObjectMapper mapper, InboundMessageService inbound, AdminCommandService admin,
                                    ClinicRepository clinics, GatewayRouter router,
                                    @Value("${siletry.gupshup.webhook-secret}") String secret) {
        this.mapper = mapper;
        this.inbound = inbound;
        this.admin = admin;
        this.clinics = clinics;
        this.router = router;
        this.secret = secret == null ? "" : secret.trim();
    }

    /** Gupshup may ping the URL when it's saved. */
    @GetMapping("/{key}")
    public ResponseEntity<String> ping(@PathVariable String key) {
        return authorized(key) ? ResponseEntity.ok("ok") : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @PostMapping("/{key}")
    public ResponseEntity<Void> receive(@PathVariable String key, @RequestBody(required = false) byte[] raw) {
        if (!authorized(key)) {
            log.warn("Rejected Gupshup webhook with a wrong key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (raw == null || raw.length == 0) return ResponseEntity.ok().build(); // URL validation
        JsonNode root;
        try {
            root = mapper.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        String appId = root.path("gs_app_id").asText(null);
        if (appId == null) appId = root.path("app").asText(null);
        WebhookBatch batch = MetaCloudApiGateway.parse(root, true);

        boolean adminApp = appId != null && appId.equals(router.adminGupshupAppId());
        Optional<Clinic> clinic = adminApp || appId == null ? Optional.empty() : clinics.findByGupshupAppId(appId);

        for (InboundMessage m : batch.messages()) {
            try {
                if (adminApp) {
                    admin.handle(m.fromPhone(), m.text());
                } else if (clinic.isPresent()) {
                    inbound.handleForClinic(clinic.get(), m);
                } else {
                    inbound.handle(m); // by phone_number_id or number
                }
            } catch (Exception e) {
                // Only storing the message can fail here (bot errors are handled inside); let Gupshup retry
                log.error("Failed to process Gupshup message {} (app {})", m.externalId(), appId, e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
        batch.statuses().forEach(s -> {
            try {
                inbound.applyStatus(s);
            } catch (Exception e) {
                log.warn("Status update failed: {}", e.getMessage());
            }
        });
        return ResponseEntity.ok().build();
    }

    private boolean authorized(String key) {
        return secret.length() >= 24 && key != null
                && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }
}
