package com.siletry.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.CredentialCipher;
import com.siletry.messaging.gateway.GatewayRouter;
import com.siletry.messaging.gateway.MetaCloudApiGateway;
import com.siletry.messaging.gateway.WhatsAppGateway.InboundMessage;
import com.siletry.messaging.gateway.WhatsAppGateway.WebhookBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Webhook for Meta's WhatsApp Cloud API. Point every Meta app (yours and each pilot clinic's) at:
 *   https://<backend>/api/webhooks/meta   with verify token = META_VERIFY_TOKEN
 * and subscribe to the "messages" field.
 */
@RestController
@RequestMapping("/api/webhooks/meta")
public class MetaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookController.class);

    private final ObjectMapper mapper;
    private final InboundMessageService inbound;
    private final AdminCommandService admin;
    private final ClinicRepository clinics;
    private final CredentialCipher cipher;
    private final GatewayRouter router;
    private final String verifyToken;
    private final String appSecret;

    public MetaWebhookController(ObjectMapper mapper, InboundMessageService inbound, AdminCommandService admin,
                                 ClinicRepository clinics, CredentialCipher cipher, GatewayRouter router,
                                 @Value("${siletry.meta.verify-token}") String verifyToken,
                                 @Value("${siletry.meta.app-secret}") String appSecret) {
        this.mapper = mapper;
        this.inbound = inbound;
        this.admin = admin;
        this.clinics = clinics;
        this.cipher = cipher;
        this.router = router;
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
    }

    /** Meta calls this once when you save the webhook URL. */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && !verifyToken.isBlank() && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verify token doesn't match");
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                        @RequestBody byte[] raw) {
        String body = new String(raw, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        // Each Meta app signs with its own secret: ours, or the pilot clinic's.
        Set<String> secrets = new LinkedHashSet<>();
        if (!appSecret.isBlank()) secrets.add(appSecret);
        for (String id : MetaCloudApiGateway.phoneNumberIds(root)) {
            clinics.findByWaPhoneNumberId(id).map(c -> cipher.decrypt(c.getWaAppSecret())).filter(Objects::nonNull).ifPresent(secrets::add);
        }
        if (secrets.isEmpty() || !validSignature(signature, raw, secrets)) {
            log.warn("Rejected WhatsApp webhook with a bad or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        WebhookBatch batch = MetaCloudApiGateway.parse(root);
        String adminId = router.adminPhoneNumberId();
        for (InboundMessage m : batch.messages()) {
            try {
                if (adminId != null && adminId.equals(m.accountId())) {
                    admin.handle(m.fromPhone(), m.text());
                } else {
                    inbound.handle(m);
                }
            } catch (Exception e) {
                log.error("Failed to process WhatsApp message {}", m.externalId(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build(); // Meta retries; duplicates are ignored
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

    private static boolean validSignature(String header, byte[] body, Set<String> secrets) {
        if (header == null || !header.startsWith("sha256=")) return false;
        byte[] given = header.substring(7).getBytes(StandardCharsets.UTF_8);
        for (String secret : secrets) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String expected = HexFormat.of().formatHex(mac.doFinal(body));
                if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given)) return true;
            } catch (Exception ignored) {
                // try the next secret
            }
        }
        return false;
    }
}
