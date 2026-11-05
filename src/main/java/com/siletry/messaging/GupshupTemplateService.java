package com.siletry.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.siletry.clinic.Clinic;
import com.siletry.common.CredentialCipher;
import com.siletry.messaging.MetaTemplateService.SyncResult;
import com.siletry.messaging.gateway.GupshupClient;
import com.siletry.messaging.gateway.MetaGraphClient.MetaApiException;
import com.siletry.template.MessageTemplate;
import com.siletry.template.MessageTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Siletry's templates for clinics on Gupshup: submitted with Gupshup's template API
 * (Meta-format template creation isn't supported through their passthrough), status pulled back.
 * Called from MetaTemplateService, which already holds the transaction.
 */
@Service
public class GupshupTemplateService {

    private static final Logger log = LoggerFactory.getLogger(GupshupTemplateService.class);

    private final GupshupClient gupshup;
    private final CredentialCipher cipher;
    private final MessageTemplateRepository templates;

    public GupshupTemplateService(GupshupClient gupshup, CredentialCipher cipher, MessageTemplateRepository templates) {
        this.gupshup = gupshup;
        this.cipher = cipher;
        this.templates = templates;
    }

    public SyncResult submitAll(Clinic c) {
        String token = cipher.decrypt(c.getWaAccessToken());
        Map<String, JsonNode> existing = fetch(c, token);
        List<String> errors = new ArrayList<>();
        int submitted = 0;
        for (TemplateCatalog.Spec spec : TemplateCatalog.CLINIC) {
            if (existing.containsKey(spec.name())) continue;
            try {
                gupshup.createTemplate(c.getGupshupAppId(), token, fields(spec));
                submitted++;
            } catch (MetaApiException e) {
                errors.add(spec.name() + ": " + e.getMessage());
                log.warn("Template {} not submitted for clinic {} (Gupshup): {}", spec.name(), c.getId(), e.getMessage());
            }
        }
        SyncResult r = sync(c);
        List<String> all = new ArrayList<>(errors);
        all.addAll(r.errors());
        return new SyncResult(submitted, r.approved(), r.pending(), r.rejected(), all);
    }

    public SyncResult sync(Clinic c) {
        String token = cipher.decrypt(c.getWaAccessToken());
        Map<String, JsonNode> remote = fetch(c, token);
        int approved = 0, pending = 0, rejected = 0;
        for (MessageTemplate t : templates.findByClinicIdOrderByNameAsc(c.getId())) {
            JsonNode r = remote.get(t.getName());
            if (r == null) continue;
            String status = r.path("status").asText("").toUpperCase(Locale.ROOT);
            switch (status) {
                case "APPROVED", "ENABLED" -> { t.setStatus(MessageTemplate.Status.APPROVED); approved++; }
                case "REJECTED", "FAILED", "DISABLED", "PAUSED", "DELETED" -> {
                    t.setStatus(MessageTemplate.Status.REJECTED);
                    t.setRejectionReason(r.path("reason").asText(r.path("rejectedReason").asText(status)));
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
        JsonNode res = gupshup.templates(c.getGupshupAppId(), token);
        for (JsonNode t : res.path("templates")) {
            String name = t.path("elementName").asText(t.path("name").asText(""));
            String lang = t.path("languageCode").asText("");
            if (name.isBlank()) continue;
            if (lang.startsWith("en") || !byName.containsKey(name)) byName.put(name, t);
        }
        return byName;
    }

    /** Form fields for Gupshup's "apply for template" API (TEXT template). */
    static Map<String, Object> fields(TemplateCatalog.Spec spec) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("elementName", spec.name());
        f.put("languageCode", "en");
        f.put("category", spec.category());
        f.put("templateType", "TEXT");
        f.put("vertical", "Clinic appointments");
        f.put("content", spec.body());
        f.put("example", example(spec));
        f.put("enableSample", "true");
        f.put("allowTemplateCategoryChange", "false");
        if (!spec.quickReplies().isEmpty()) {
            List<Map<String, Object>> buttons = new ArrayList<>();
            for (String q : spec.quickReplies()) buttons.add(Map.of("type", "QUICK_REPLY", "text", q));
            f.put("buttons", buttons); // sent as a JSON string
        }
        return f;
    }

    /** The template body with {{n}} replaced by the sample values. */
    static String example(TemplateCatalog.Spec spec) {
        String s = spec.body();
        for (int i = 0; i < spec.example().size(); i++) s = s.replace("{{" + (i + 1) + "}}", spec.example().get(i));
        return s;
    }
}
