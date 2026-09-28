package com.siletry.messaging;

import com.siletry.messaging.gateway.MockWhatsAppGateway;
import com.siletry.messaging.gateway.WhatsAppGateway.WebhookBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Simple JSON webhook for testing without Meta (curl/Postman). Only with DEV_TOOLS=true.
 * Real WhatsApp traffic arrives at /api/webhooks/meta.
 */
@RestController
@RequestMapping("/api/webhooks/whatsapp")
@ConditionalOnProperty(name = "siletry.dev-tools", havingValue = "true")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final MockWhatsAppGateway mock;
    private final InboundMessageService inbound;
    private final AdminCommandService admin;
    private final String secret;

    public WebhookController(MockWhatsAppGateway mock, InboundMessageService inbound, AdminCommandService admin,
                             @Value("${siletry.dev-webhook-secret}") String secret) {
        this.mock = mock;
        this.inbound = inbound;
        this.admin = admin;
        this.secret = secret;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> patient(@RequestHeader(value = "X-Siletry-Webhook-Secret", required = false) String header,
                                                       @RequestBody String body) {
        if (!secret.equals(header)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        WebhookBatch batch = mock.parseDevWebhook(body);
        int handled = 0;
        for (var m : batch.messages()) {
            try {
                if (inbound.handle(m) != null) handled++;
            } catch (Exception e) {
                log.error("Failed to process inbound message {}", m.externalId(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
        batch.statuses().forEach(inbound::applyStatus);
        return ResponseEntity.ok(Map.of("messages", handled, "statuses", batch.statuses().size()));
    }

    @PostMapping("/admin")
    public ResponseEntity<Map<String, Object>> adminNumber(@RequestHeader(value = "X-Siletry-Webhook-Secret", required = false) String header,
                                                           @RequestBody String body) {
        if (!secret.equals(header)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        WebhookBatch batch = mock.parseDevWebhook(body);
        batch.messages().forEach(m -> admin.handle(m.fromPhone(), m.text()));
        return ResponseEntity.ok(Map.of("messages", batch.messages().size()));
    }
}
