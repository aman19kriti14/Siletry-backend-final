package com.siletry.clinic;

import com.siletry.common.ApiException;
import com.siletry.common.CredentialCipher;
import com.siletry.common.Phone;
import com.siletry.messaging.MetaTemplateService;
import com.siletry.messaging.gateway.GupshupClient;
import com.siletry.messaging.gateway.MetaGraphClient.MetaApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Connects a clinic to its Gupshup app (created in the Gupshup Partner Portal, number onboarded through
 * Gupshup's signup link): fetches the app token, stores it encrypted, points the app's webhooks at
 * Siletry and submits the templates.
 */
@Service
public class GupshupConnectService {

    private static final Logger log = LoggerFactory.getLogger(GupshupConnectService.class);

    public record Result(Long clinicId, String gupshupAppId, String whatsappNumber, boolean webhookSubscribed,
                         int templatesSubmitted, List<String> warnings, String webhookUrl) {}

    private final ClinicRepository clinics;
    private final GupshupClient gupshup;
    private final CredentialCipher cipher;
    private final MetaTemplateService templates;
    private final String publicBaseUrl;
    private final String webhookSecret;

    public GupshupConnectService(ClinicRepository clinics, GupshupClient gupshup, CredentialCipher cipher,
                                 MetaTemplateService templates,
                                 @Value("${siletry.public-base-url}") String publicBaseUrl,
                                 @Value("${siletry.gupshup.webhook-secret}") String webhookSecret) {
        this.clinics = clinics;
        this.gupshup = gupshup;
        this.cipher = cipher;
        this.templates = templates;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
    }

    /**
     * appToken: optional. When empty the token is fetched with the partner login.
     * whatsappNumber: the clinic's WhatsApp number with country code.
     * phoneNumberId: optional Meta phone number ID behind the app (helps route webhooks).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Result connect(Long clinicId, String appId, String appToken, String whatsappNumber, String phoneNumberId) {
        if (appId == null || appId.isBlank()) throw ApiException.badRequest("Gupshup app ID is required");
        if (whatsappNumber == null || Phone.normalize(whatsappNumber) == null)
            throw ApiException.badRequest("The clinic's WhatsApp number (with country code) is required");
        if (webhookSecret.length() < 24)
            throw ApiException.badRequest("Set GUPSHUP_WEBHOOK_SECRET (24+ characters) on the backend first");

        String id = appId.trim();
        String number = Phone.normalize(whatsappNumber);
        Clinic c = clinics.findById(clinicId).orElseThrow(() -> ApiException.notFound("Clinic"));
        clinics.findByGupshupAppId(id).filter(o -> !o.getId().equals(c.getId()))
                .ifPresent(o -> { throw ApiException.conflict("This Gupshup app is already connected to clinic " + o.getId()); });
        clinics.findByWhatsappNumber(number).filter(o -> !o.getId().equals(c.getId()))
                .ifPresent(o -> { throw ApiException.conflict("This number is linked to clinic " + o.getId()); });
        String pnid = phoneNumberId == null || phoneNumberId.isBlank() ? null : phoneNumberId.trim();
        if (pnid != null) {
            clinics.findByWaPhoneNumberId(pnid).filter(o -> !o.getId().equals(c.getId()))
                    .ifPresent(o -> { throw ApiException.conflict("This phone number ID is linked to clinic " + o.getId()); });
        }

        String token;
        try {
            token = appToken != null && !appToken.isBlank() ? appToken.trim() : gupshup.appToken(id);
        } catch (MetaApiException e) {
            throw ApiException.badRequest("Couldn't get the app token from Gupshup: " + e.getMessage());
        }

        c.setGupshupAppId(id);
        c.setWaAccessToken(cipher.encrypt(token));
        c.setWaPhoneNumberId(pnid);
        c.setWaBusinessAccountId(null);
        c.setWaAppSecret(null);
        c.setWaConnectedAt(LocalDateTime.now());
        c.setWhatsappNumber(number);
        c.setWhatsappConnected(true);
        c.setWhatsappNumberChoice("CURRENT");
        clinics.flush();

        List<String> warnings = new ArrayList<>();
        String url = webhookUrl();
        boolean subscribed = false;
        if (url == null) {
            warnings.add("PUBLIC_BASE_URL isn't set, so the webhook wasn't subscribed. Set it in the Gupshup portal by hand.");
        } else {
            try {
                gupshup.subscribe(id, token, url);
                subscribed = true;
            } catch (MetaApiException e) {
                warnings.add("Webhook not subscribed automatically (" + e.getMessage()
                        + "). In the Gupshup portal add a v3 (Meta format) subscription for this app to the webhook URL.");
            }
        }

        int submitted = 0;
        try {
            var r = templates.submitAll(c);
            submitted = r.submitted();
            warnings.addAll(r.errors());
        } catch (Exception e) {
            warnings.add("Templates: " + e.getMessage());
        }
        log.info("Clinic {} connected Gupshup app {} ({})", c.getId(), id, number);
        return new Result(c.getId(), id, Phone.pretty(number), subscribed, submitted, warnings, url == null ? null : maskedUrl());
    }

    private String webhookUrl() {
        if (publicBaseUrl.isBlank()) return null;
        return publicBaseUrl + "/api/webhooks/gupshup/" + webhookSecret;
    }

    /** Never echo the secret back in API responses. */
    private String maskedUrl() {
        return publicBaseUrl + "/api/webhooks/gupshup/<GUPSHUP_WEBHOOK_SECRET>";
    }
}
