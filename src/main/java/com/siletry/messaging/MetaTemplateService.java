package com.siletry.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.siletry.clinic.Clinic;
import com.siletry.common.CredentialCipher;
import com.siletry.messaging.gateway.MetaGraphClient;
import com.siletry.messaging.gateway.MetaGraphClient.MetaApiException;
import com.siletry.template.MessageTemplate;
import com.siletry.template.MessageTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Creates Siletry's templates in a clinic's WhatsApp account and keeps their approval status in sync. */
@Service
public class MetaTemplateService {

    private static final Logger log = LoggerFactory.getLogger(MetaTemplateService.class);

    public record SyncResult(int submitted, int approved, int pending, int rejected, List<String> errors) {}

    private final MetaGraphClient graph;
    private final CredentialCipher cipher;
    private final MessageTemplateRepository templates;

    public MetaTemplateService(MetaGraphClient graph, CredentialCipher cipher, MessageTemplateRepository templates) {
        this.graph = graph;
        this.cipher = cipher;
        this.templates = templates;
    }

    /** Makes sure every catalog template exists as a row for the clinic (for the Templates page). */
    @Transactional
    public void seed(Long clinicId) {
        Set<String> have = new HashSet<>();
        templates.findByClinicIdOrderByNameAsc(clinicId).forEach(t -> have.add(t.getName()));
        for (TemplateCatalog.Spec spec : TemplateCatalog.CLINIC) {
            if (have.contains(spec.name())) continue;
            MessageTemplate t = new MessageTemplate();
            t.setClinicId(clinicId);
            t.setName(spec.name());
            t.setCategory(MessageTemplate.Category.UTILITY);
            t.setLanguage("en");
            t.setBody(spec.body());
            templates.save(t);
        }
    }

    /** Submits any catalog template the clinic's account doesn't have yet, then refreshes statuses. */
    @Transactional(noRollbackFor = {MetaApiException.class, com.siletry.common.ApiException.class})
    public SyncResult submitAll(Clinic c) {
        requireConnected(c);
        seed(c.getId());
        String token = cipher.decrypt(c.getWaAccessToken());
        Map<String, JsonNode> existing = fetch(c, token);
        List<String> errors = new ArrayList<>();
        int submitted = 0;
        for (TemplateCatalog.Spec spec : TemplateCatalog.CLINIC) {
            if (existing.containsKey(spec.name())) continue;
            try {
                graph.post(c.getWaBusinessAccountId() + "/message_templates", token, body(spec));
                submitted++;
            } catch (MetaApiException e) {
                errors.add(spec.name() + ": " + e.getMessage());
                log.warn("Template {} not submitted for clinic {}: {}", spec.name(), c.getId(), e.getMessage());
            }
        }
        SyncResult r = sync(c);
        List<String> all = new ArrayList<>(errors);
        all.addAll(r.errors());
        return new SyncResult(submitted, r.approved(), r.pending(), r.rejected(), all);
    }

    /** Pulls approval status for every template from Meta. */
    @Transactional(noRollbackFor = {MetaApiException.class, com.siletry.common.ApiException.class})
    public SyncResult sync(Clinic c) {
        requireConnected(c);
        seed(c.getId());
        String token = cipher.decrypt(c.getWaAccessToken());
        Map<String, JsonNode> remote = fetch(c, token);
        int approved = 0, pending = 0, rejected = 0;
        for (MessageTemplate t : templates.findByClinicIdOrderByNameAsc(c.getId())) {
            JsonNode r = remote.get(t.getName());
            if (r == null) continue;
            String status = r.path("status").asText("");
            switch (status) {
                case "APPROVED" -> { t.setStatus(MessageTemplate.Status.APPROVED); approved++; }
                case "REJECTED", "DISABLED", "PAUSED" -> {
                    t.setStatus(MessageTemplate.Status.REJECTED);
                    t.setRejectionReason(r.path("rejected_reason").asText(status));
                    rejected++;
                }
                default -> { t.setStatus(MessageTemplate.Status.SUBMITTED); pending++; }
            }
            t.setProviderTemplateId(r.path("id").asText(null));
        }
        return new SyncResult(0, approved, pending, rejected, List.of());
    }

    private Map<String, JsonNode> fetch(Clinic c, String token) {
        Map<String, JsonNode> byName = new HashMap<>();
        JsonNode res = graph.get(c.getWaBusinessAccountId() + "/message_templates?fields=name,status,language,rejected_reason,id&limit=200", token);
        for (JsonNode t : res.path("data")) {
            if ("en".equals(t.path("language").asText()) || !byName.containsKey(t.path("name").asText())) {
                byName.put(t.path("name").asText(), t);
            }
        }
        return byName;
    }

    private static Map<String, Object> body(TemplateCatalog.Spec spec) {
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(Map.of("type", "BODY", "text", spec.body(), "example", Map.of("body_text", List.of(spec.example()))));
        if (!spec.quickReplies().isEmpty()) {
            List<Map<String, Object>> buttons = new ArrayList<>();
            for (String q : spec.quickReplies()) buttons.add(Map.of("type", "QUICK_REPLY", "text", q));
            components.add(Map.of("type", "BUTTONS", "buttons", buttons));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", spec.name());
        b.put("language", "en");
        b.put("category", spec.category());
        b.put("components", components);
        return b;
    }

    private static void requireConnected(Clinic c) {
        if (!c.hasWhatsAppApi() || c.getWaBusinessAccountId() == null) {
            throw com.siletry.common.ApiException.badRequest("Connect the clinic's WhatsApp first");
        }
    }
}
