package com.siletry.messaging.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.siletry.messaging.Message;
import com.siletry.messaging.gateway.MetaGraphClient.MetaApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/** Real WhatsApp through Meta's Cloud API. */
@Component
public class MetaCloudApiGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(MetaCloudApiGateway.class);
    private static final int BUTTON_TITLE_MAX = 20;
    private static final int LIST_TITLE_MAX = 24;

    private final MetaGraphClient graph;

    public MetaCloudApiGateway(MetaGraphClient graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "meta";
    }

    @Override
    public SendResult sendText(WaAccount from, String to, String body) {
        Map<String, Object> msg = base(to, "text");
        msg.put("text", Map.of("body", clip(body, 4096), "preview_url", false));
        return send(from, msg);
    }

    @Override
    public SendResult sendButtons(WaAccount from, String to, String body, List<String> buttons) {
        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("body", Map.of("text", clip(body, 1024)));
        if (buttons.size() <= 3) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (String b : buttons) {
                // id carries the full label so the bot can match it even when the title is shortened
                list.add(Map.of("type", "reply", "reply", Map.of("id", clip(b, 256), "title", clip(b, BUTTON_TITLE_MAX))));
            }
            interactive.put("type", "button");
            interactive.put("action", Map.of("buttons", list));
        } else {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String b : buttons.subList(0, Math.min(10, buttons.size()))) {
                rows.add(Map.of("id", clip(b, 200), "title", clip(b, LIST_TITLE_MAX)));
            }
            interactive.put("type", "list");
            interactive.put("action", Map.of("button", "Choose", "sections", List.of(Map.of("title", "Options", "rows", rows))));
        }
        Map<String, Object> msg = base(to, "interactive");
        msg.put("interactive", interactive);
        return send(from, msg);
    }

    @Override
    public SendResult sendTemplate(WaAccount from, String to, TemplateMessage t) {
        List<Map<String, Object>> components = new ArrayList<>();
        if (t.bodyParams() != null && !t.bodyParams().isEmpty()) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (String p : t.bodyParams()) params.add(Map.of("type", "text", "text", cleanParam(p)));
            components.add(Map.of("type", "body", "parameters", params));
        }
        if (t.codeParam() != null) {
            components.add(Map.of("type", "button", "sub_type", "url", "index", "0",
                    "parameters", List.of(Map.of("type", "text", "text", t.codeParam()))));
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", t.name());
        template.put("language", Map.of("code", t.language() == null ? "en" : t.language()));
        if (!components.isEmpty()) template.put("components", components);
        Map<String, Object> msg = base(to, "template");
        msg.put("template", template);
        return send(from, msg);
    }

    private SendResult send(WaAccount from, Map<String, Object> msg) {
        if (from == null || from.phoneNumberId() == null || from.accessToken() == null) {
            return SendResult.failed("WhatsApp isn't connected for this number");
        }
        try {
            JsonNode res = graph.post(from.phoneNumberId() + "/messages", from.accessToken(), msg);
            return SendResult.ok(res.path("messages").path(0).path("id").asText(null));
        } catch (MetaApiException e) {
            log.warn("WhatsApp send failed ({}): {}", e.getCode(), e.getMessage());
            return SendResult.failed(friendly(e), e.getCode());
        }
    }

    private static Map<String, Object> base(String to, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messaging_product", "whatsapp");
        m.put("recipient_type", "individual");
        m.put("to", to);
        m.put("type", type);
        return m;
    }

    private static String friendly(MetaApiException e) {
        return switch (e.getCode()) {
            case 131047 -> "More than 24 hours since the patient's last message. Only an approved template can be sent.";
            case 132001 -> "The template isn't approved yet (or its name/language doesn't match). " + e.getMessage();
            case 131026 -> "This number isn't on WhatsApp or can't receive messages.";
            case 190 -> "The WhatsApp access token has expired or is wrong. Reconnect WhatsApp.";
            case 131030 -> "This number isn't allowed yet. On a Meta test number, add it to the allowed recipients.";
            default -> e.getMessage();
        };
    }

    /** Template parameters can't contain newlines, tabs or more than 4 spaces in a row. */
    private static String cleanParam(String p) {
        if (p == null || p.isBlank()) return "-";
        return clip(p.replaceAll("[\\n\\t]+", " ").replaceAll(" {4,}", "   ").trim(), 1024);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------
    // Webhook parsing
    // ------------------------------------------------------------------

    /** Phone number IDs the webhook body is about (to pick the right app secret). */
    public static Set<String> phoneNumberIds(JsonNode root) {
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                String id = change.path("value").path("metadata").path("phone_number_id").asText(null);
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }

    public static WebhookBatch parse(JsonNode root) {
        List<InboundMessage> messages = new ArrayList<>();
        List<StatusUpdate> statuses = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode v = change.path("value");
                String phoneNumberId = v.path("metadata").path("phone_number_id").asText(null);
                String display = v.path("metadata").path("display_phone_number").asText(null);
                Map<String, String> names = new HashMap<>();
                for (JsonNode c : v.path("contacts")) names.put(c.path("wa_id").asText(), c.path("profile").path("name").asText(null));

                for (JsonNode m : v.path("messages")) {
                    String from = m.path("from").asText();
                    messages.add(new InboundMessage(display, from, names.get(from), textOf(m), m.path("id").asText(null), phoneNumberId));
                }
                for (JsonNode s : v.path("statuses")) {
                    Message.Status st = switch (s.path("status").asText()) {
                        case "sent" -> Message.Status.SENT;
                        case "delivered" -> Message.Status.DELIVERED;
                        case "read" -> Message.Status.READ;
                        case "failed" -> Message.Status.FAILED;
                        default -> null;
                    };
                    if (st == null) continue;
                    String err = s.path("errors").path(0).path("title").asText(null);
                    String detail = s.path("errors").path(0).path("error_data").path("details").asText(null);
                    statuses.add(new StatusUpdate(s.path("id").asText(), st, detail != null ? err + ": " + detail : err));
                }
            }
        }
        return new WebhookBatch(messages, statuses);
    }

    private static String textOf(JsonNode m) {
        return switch (m.path("type").asText()) {
            case "text" -> m.path("text").path("body").asText("");
            case "interactive" -> {
                JsonNode i = m.path("interactive");
                JsonNode r = i.has("button_reply") ? i.path("button_reply") : i.path("list_reply");
                String id = r.path("id").asText("");
                yield id.isBlank() ? r.path("title").asText("") : id;
            }
            case "button" -> m.path("button").path("text").asText(m.path("button").path("payload").asText(""));
            case "image" -> "[Photo]" + caption(m.path("image"));
            case "document" -> "[Document]" + caption(m.path("document"));
            case "audio" -> "[Voice note]";
            case "video" -> "[Video]" + caption(m.path("video"));
            case "location" -> "[Location] " + m.path("location").path("name").asText("") + " " + m.path("location").path("address").asText("");
            case "sticker" -> "[Sticker]";
            case "contacts" -> "[Contact card]";
            default -> "[Unsupported message]";
        };
    }

    private static String caption(JsonNode media) {
        String c = media.path("caption").asText("");
        return c.isBlank() ? "" : " " + c;
    }
}
