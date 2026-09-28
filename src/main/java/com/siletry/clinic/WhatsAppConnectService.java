package com.siletry.clinic;

import com.fasterxml.jackson.databind.JsonNode;
import com.siletry.common.ApiException;
import com.siletry.common.CredentialCipher;
import com.siletry.common.Phone;
import com.siletry.messaging.MetaTemplateService;
import com.siletry.messaging.gateway.MetaGraphClient;
import com.siletry.messaging.gateway.MetaGraphClient.MetaApiException;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Connects a clinic's own WhatsApp Cloud API number (set up by hand in the clinic's Meta account).
 * Checks the details with Meta, stores them encrypted, subscribes the app to webhooks and submits templates.
 */
@Service
public class WhatsAppConnectService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppConnectService.class);

    public record Credentials(@NotBlank String phoneNumberId, @NotBlank String businessAccountId,
                              @NotBlank String accessToken, String appSecret, String pin) {}

    public record ConnectResult(String displayNumber, String verifiedName, String qualityRating,
                                int templatesSubmitted, List<String> warnings) {}

    private final ClinicRepository clinics;
    private final MetaGraphClient graph;
    private final CredentialCipher cipher;
    private final MetaTemplateService templates;

    public WhatsAppConnectService(ClinicRepository clinics, MetaGraphClient graph, CredentialCipher cipher,
                                  MetaTemplateService templates) {
        this.clinics = clinics;
        this.graph = graph;
        this.cipher = cipher;
        this.templates = templates;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public ConnectResult connect(Long clinicId, Credentials req) {
        Clinic c = clinics.findById(clinicId).orElseThrow(() -> ApiException.notFound("Clinic"));
        String phoneId = req.phoneNumberId().trim();
        String wabaId = req.businessAccountId().trim();
        String token = req.accessToken().trim();

        clinics.findByWaPhoneNumberId(phoneId).filter(o -> !o.getId().equals(c.getId()))
                .ifPresent(o -> { throw ApiException.conflict("This WhatsApp number is already connected to another clinic"); });

        JsonNode info;
        try {
            info = graph.get(phoneId + "?fields=display_phone_number,verified_name,quality_rating", token);
        } catch (MetaApiException e) {
            throw ApiException.badRequest("Meta didn't accept these details: " + e.getMessage());
        }
        List<String> warnings = new ArrayList<>();

        if (req.pin() != null && req.pin().matches("\\d{6}")) {
            try {
                graph.post(phoneId + "/register", token, Map.of("messaging_product", "whatsapp", "pin", req.pin()));
            } catch (MetaApiException e) {
                warnings.add("Registering the number: " + e.getMessage());
            }
        }
        try {
            graph.post(wabaId + "/subscribed_apps", token, Map.of());
        } catch (MetaApiException e) {
            warnings.add("Subscribing to incoming messages: " + e.getMessage()
                    + ". Check the WhatsApp Business Account ID and that the token has whatsapp_business_management permission.");
        }

        String display = info.path("display_phone_number").asText(null);
        c.setWaPhoneNumberId(phoneId);
        c.setWaBusinessAccountId(wabaId);
        c.setWaAccessToken(cipher.encrypt(token));
        if (req.appSecret() != null && !req.appSecret().isBlank()) c.setWaAppSecret(cipher.encrypt(req.appSecret().trim()));
        c.setWaVerifiedName(info.path("verified_name").asText(null));
        c.setWaConnectedAt(LocalDateTime.now());
        if (display != null) {
            String n = Phone.normalize(display);
            clinics.findByWhatsappNumber(n).filter(o -> !o.getId().equals(c.getId()))
                    .ifPresent(o -> { throw ApiException.conflict("This number is linked to another clinic"); });
            c.setWhatsappNumber(n);
        }
        c.setWhatsappConnected(true);
        c.setWhatsappNumberChoice("CURRENT");
        clinics.flush();

        int submitted = 0;
        try {
            var r = templates.submitAll(c);
            submitted = r.submitted();
            warnings.addAll(r.errors());
        } catch (Exception e) {
            warnings.add("Templates: " + e.getMessage());
        }
        log.info("Clinic {} connected WhatsApp {} ({})", c.getId(), display, phoneId);
        return new ConnectResult(Phone.pretty(c.getWhatsappNumber()), c.getWaVerifiedName(),
                info.path("quality_rating").asText(null), submitted, warnings);
    }

    @Transactional
    public void disconnect(Clinic c) {
        c.setWaPhoneNumberId(null);
        c.setWaBusinessAccountId(null);
        c.setWaAccessToken(null);
        c.setWaAppSecret(null);
        c.setWaVerifiedName(null);
        c.setWaConnectedAt(null);
        c.setWhatsappConnected(false);
    }
}
