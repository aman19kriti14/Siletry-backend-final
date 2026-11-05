package com.siletry.ops;

import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * For the Siletry team: see every clinic and mark the paid ones. Same X-Ops-Key as /api/ops/leads.
 *
 *   GET  /api/ops/clinics
 *   POST /api/ops/clinics/{id}/plan   {"plan":"ACTIVE"}              paid
 *                                     {"plan":"TRIAL","trialDays":7} extend the trial from today
 *                                     {"plan":"PAUSED"}              stopped paying (bot goes quiet)
 *   POST /api/ops/clinics/{id}/message-cap {"cap":3000}
 *   POST /api/ops/clinics/{id}/gupshup     {"appId":"...","whatsappNumber":"919845012345"}
 *        optional "appToken" (else fetched with the partner login) and "phoneNumberId"
 *   POST /api/ops/clinics/{id}/templates/sync  refresh template approval status
 */
@RestController
@RequestMapping("/api/ops/clinics")
public class OpsClinicController {

    public record ClinicRow(Long id, String name, String whatsappNumber, boolean whatsappConnected, String provider,
                            boolean botEnabled, String plan, LocalDateTime trialEndsAt, int messageCap, LocalDateTime createdAt) {
        static ClinicRow of(Clinic c) {
            String provider = !c.hasWhatsAppApi() ? "none" : c.isGupshup() ? "gupshup" : "meta";
            return new ClinicRow(c.getId(), c.getName(), c.getWhatsappNumber(), c.hasWhatsAppApi(), provider, c.isBotEnabled(),
                    c.getPlan().name(), c.getTrialEndsAt(), c.getMessageCap(), c.getCreatedAt());
        }
    }

    public record PlanRequest(Clinic.Plan plan, Integer trialDays) {}

    public record CapRequest(int cap) {}

    public record GupshupRequest(String appId, String appToken, String whatsappNumber, String phoneNumberId) {}

    private final ClinicRepository clinics;
    private final OpsProperties props;
    private final com.siletry.clinic.GupshupConnectService gupshup;
    private final com.siletry.messaging.MetaTemplateService templates;

    public OpsClinicController(ClinicRepository clinics, OpsProperties props,
                               com.siletry.clinic.GupshupConnectService gupshup,
                               com.siletry.messaging.MetaTemplateService templates) {
        this.clinics = clinics;
        this.props = props;
        this.gupshup = gupshup;
        this.templates = templates;
    }

    @PostMapping("/{id}/gupshup")
    public com.siletry.clinic.GupshupConnectService.Result connectGupshup(
            @RequestHeader(value = "X-Ops-Key", required = false) String key,
            @PathVariable Long id, @RequestBody GupshupRequest req) {
        authorize(key);
        return gupshup.connect(id, req.appId(), req.appToken(), req.whatsappNumber(), req.phoneNumberId());
    }

    @PostMapping("/{id}/templates/sync")
    @Transactional(noRollbackFor = {ApiException.class, com.siletry.messaging.gateway.MetaGraphClient.MetaApiException.class})
    public com.siletry.messaging.MetaTemplateService.SyncResult syncTemplates(
            @RequestHeader(value = "X-Ops-Key", required = false) String key, @PathVariable Long id) {
        authorize(key);
        Clinic c = clinics.findById(id).orElseThrow(() -> ApiException.notFound("Clinic"));
        return templates.submitAll(c);
    }

    @GetMapping
    public List<ClinicRow> list(@RequestHeader(value = "X-Ops-Key", required = false) String key) {
        authorize(key);
        return clinics.findAll().stream()
                .sorted(Comparator.comparing(Clinic::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ClinicRow::of).toList();
    }

    @PostMapping("/{id}/plan")
    @Transactional
    public ClinicRow setPlan(@RequestHeader(value = "X-Ops-Key", required = false) String key,
                             @PathVariable Long id, @RequestBody PlanRequest req) {
        authorize(key);
        if (req.plan() == null) throw ApiException.badRequest("plan is required: TRIAL, ACTIVE or PAUSED");
        Clinic c = clinics.findById(id).orElseThrow(() -> ApiException.notFound("Clinic"));
        c.setPlan(req.plan());
        if (req.plan() == Clinic.Plan.TRIAL && req.trialDays() != null) {
            c.setTrialEndsAt(LocalDateTime.now().plusDays(Math.max(1, req.trialDays())));
        }
        // A paused clinic's patients go to the inbox instead of the bot; re-activating turns it back on
        if (req.plan() == Clinic.Plan.PAUSED) c.setBotEnabled(false);
        if (req.plan() == Clinic.Plan.ACTIVE) c.setBotEnabled(true);
        return ClinicRow.of(c);
    }

    @PostMapping("/{id}/message-cap")
    @Transactional
    public ClinicRow setCap(@RequestHeader(value = "X-Ops-Key", required = false) String key,
                            @PathVariable Long id, @RequestBody CapRequest req) {
        authorize(key);
        if (req.cap() < 0) throw ApiException.badRequest("cap must be 0 or more");
        Clinic c = clinics.findById(id).orElseThrow(() -> ApiException.notFound("Clinic"));
        c.setMessageCap(req.cap());
        return ClinicRow.of(c);
    }

    private void authorize(String key) {
        if (!props.apiEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Ops API is disabled. Set OPS_API_KEY.");
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.apiKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid ops key");
        }
    }
}
