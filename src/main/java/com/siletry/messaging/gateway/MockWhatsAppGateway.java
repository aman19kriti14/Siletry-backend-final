package com.siletry.messaging.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siletry.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pretends to send. Used for clinics without WhatsApp connected, and always inside the simulator.
 * Every message is still stored, so the app shows exactly what a patient would get.
 *
 * Dev webhook body (POST /api/webhooks/whatsapp):
 *   {"type":"message","to":"918047182200","from":"919845012345","name":"Lakshmi","text":"hi","id":"abc"}
 *   {"type":"status","id":"mock-...","status":"READ"}
 */
@Component
public class MockWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(MockWhatsAppGateway.class);
    private final ObjectMapper mapper;

    public MockWhatsAppGateway(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public SendResult sendText(WaAccount from, String to, String body) {
        log.info("[WA mock] -> {}: {}", to, body);
        return SendResult.ok("mock-" + UUID.randomUUID());
    }

    @Override
    public SendResult sendButtons(WaAccount from, String to, String body, List<String> buttons) {
        log.info("[WA mock] -> {}: {} {}", to, body, buttons);
        return SendResult.ok("mock-" + UUID.randomUUID());
    }

    @Override
    public SendResult sendTemplate(WaAccount from, String to, TemplateMessage t) {
        log.info("[WA mock] -> {} TEMPLATE {} {}", to, t.name(), t.bodyParams());
        return SendResult.ok("mock-" + UUID.randomUUID());
    }

    public WebhookBatch parseDevWebhook(String rawBody) {
        List<InboundMessage> messages = new ArrayList<>();
        List<StatusUpdate> statuses = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(rawBody);
            List<JsonNode> items = new ArrayList<>();
            if (root.isArray()) root.forEach(items::add); else items.add(root);
            for (JsonNode n : items) {
                if ("status".equals(n.path("type").asText("message"))) {
                    statuses.add(new StatusUpdate(n.path("id").asText(),
                            Message.Status.valueOf(n.path("status").asText("DELIVERED").toUpperCase()),
                            n.path("error").asText(null)));
                } else {
                    messages.add(new InboundMessage(n.path("to").asText(null), n.path("from").asText(),
                            n.path("name").asText(null), n.path("text").asText(""),
                            n.path("id").asText("mock-in-" + UUID.randomUUID())));
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse dev webhook: {}", e.getMessage());
        }
        return new WebhookBatch(messages, statuses);
    }
}
