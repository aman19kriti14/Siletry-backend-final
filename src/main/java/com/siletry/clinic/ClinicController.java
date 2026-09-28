package com.siletry.clinic;

import com.siletry.auth.CurrentUser;
import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.messaging.MessageUsage;
import com.siletry.messaging.OutboundMessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** Settings, automations, WhatsApp connection and message usage. */
@RestController
@RequestMapping("/api/clinic")
public class ClinicController {

    public record ClinicView(Long id, String name, String area, String city, String address, String phone,
                             String whatsappNumber, boolean whatsappConnected, boolean botEnabled,
                             String plan, LocalDateTime trialEndsAt, int messageCap,
                             boolean remindersEnabled, LocalTime reminderTime, boolean followupsEnabled,
                             boolean queueUpdatesEnabled, String googleReviewLink,
                             boolean delayAnnouncementsEnabled, boolean googleReviewsEnabled,
                             String clinicType, String landmark, List<String> replyLanguages, boolean live,
                             boolean apiConnected, String verifiedName, String phoneNumberId, String businessAccountId,
                             java.time.LocalDateTime apiConnectedAt) {
        static ClinicView of(Clinic c) {
            return new ClinicView(c.getId(), c.getName(), c.getArea(), c.getCity(), c.getAddress(),
                    Phone.pretty(c.getPhone()), Phone.pretty(c.getWhatsappNumber()), c.isWhatsappConnected(),
                    c.isBotEnabled(), c.getPlan().name(), c.getTrialEndsAt(), c.getMessageCap(),
                    c.isRemindersEnabled(), c.getReminderTime(), c.isFollowupsEnabled(), c.isQueueUpdatesEnabled(),
                    c.getGoogleReviewLink(), c.delayAnnouncementsOn(), c.googleReviewsOn(),
                    c.getClinicType(), c.getLandmark(), c.replyLanguageList(), c.isLive(),
                    c.hasWhatsAppApi(), c.getWaVerifiedName(), c.getWaPhoneNumberId(), c.getWaBusinessAccountId(),
                    c.getWaConnectedAt());
        }
    }

    public record UpdateClinic(String name, String area, String city, String address, String phone,
                               Boolean botEnabled, Boolean remindersEnabled, LocalTime reminderTime,
                               Boolean followupsEnabled, Boolean queueUpdatesEnabled, String googleReviewLink,
                               Boolean delayAnnouncementsEnabled, Boolean googleReviewsEnabled,
                               String clinicType, String landmark, List<String> replyLanguages) {}

    public record ConnectWhatsApp(@NotBlank String number) {}

    public record UsageView(String month, int businessInitiated, int cap, int serviceReplies, int inbound,
                            int percentUsed) {}

    private final ClinicRepository clinics;
    private final OutboundMessageService outbound;
    private final WhatsAppConnectService connectService;
    private final boolean devTools;

    public ClinicController(ClinicRepository clinics, OutboundMessageService outbound, WhatsAppConnectService connectService,
                            @org.springframework.beans.factory.annotation.Value("${siletry.dev-tools}") boolean devTools) {
        this.clinics = clinics;
        this.outbound = outbound;
        this.connectService = connectService;
        this.devTools = devTools;
    }

    /** Real connection with the clinic's WhatsApp Cloud API details. */
    @PostMapping("/whatsapp/credentials")
    public WhatsAppConnectService.ConnectResult credentials(@Valid @RequestBody WhatsAppConnectService.Credentials req) {
        CurrentUser.requireOwner();
        return connectService.connect(CurrentUser.clinicId(), req);
    }

    private Clinic mine() {
        return clinics.findById(CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("Clinic"));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ClinicView get() {
        return ClinicView.of(mine());
    }

    @PatchMapping
    @Transactional
    public ClinicView update(@RequestBody UpdateClinic req) {
        CurrentUser.requireOwner();
        Clinic c = mine();
        if (req.name() != null) c.setName(req.name().trim());
        if (req.area() != null) c.setArea(req.area());
        if (req.city() != null) c.setCity(req.city());
        if (req.address() != null) c.setAddress(req.address());
        if (req.phone() != null) c.setPhone(Phone.normalize(req.phone()));
        if (req.botEnabled() != null) c.setBotEnabled(req.botEnabled());
        if (req.remindersEnabled() != null) c.setRemindersEnabled(req.remindersEnabled());
        if (req.reminderTime() != null) c.setReminderTime(req.reminderTime());
        if (req.followupsEnabled() != null) c.setFollowupsEnabled(req.followupsEnabled());
        if (req.queueUpdatesEnabled() != null) c.setQueueUpdatesEnabled(req.queueUpdatesEnabled());
        if (req.googleReviewLink() != null) c.setGoogleReviewLink(req.googleReviewLink());
        if (req.delayAnnouncementsEnabled() != null) c.setDelayAnnouncementsEnabled(req.delayAnnouncementsEnabled());
        if (req.googleReviewsEnabled() != null) c.setGoogleReviewsEnabled(req.googleReviewsEnabled());
        if (req.clinicType() != null) c.setClinicType(req.clinicType());
        if (req.landmark() != null) c.setLandmark(req.landmark().isBlank() ? null : req.landmark().trim());
        if (req.replyLanguages() != null && !req.replyLanguages().isEmpty()) c.setReplyLanguages(String.join(",", req.replyLanguages()));
        return ClinicView.of(c);
    }

    /** Test mode only (DEV_TOOLS=true): saves the number as connected without Meta. */
    @PostMapping("/whatsapp/connect")
    @Transactional
    public ClinicView connect(@Valid @RequestBody ConnectWhatsApp req) {
        CurrentUser.requireOwner();
        if (!devTools) throw ApiException.badRequest("Paste the WhatsApp API details to connect");
        Clinic c = mine();
        String number = Phone.normalize(req.number());
        clinics.findByWhatsappNumber(number).filter(o -> !o.getId().equals(c.getId()))
                .ifPresent(o -> { throw ApiException.conflict("This number is linked to another clinic"); });
        c.setWhatsappNumber(number);
        c.setWhatsappConnected(true);
        return ClinicView.of(c);
    }

    @PostMapping("/whatsapp/disconnect")
    @Transactional
    public ClinicView disconnect() {
        CurrentUser.requireOwner();
        Clinic c = mine();
        connectService.disconnect(c);
        return ClinicView.of(c);
    }

    @GetMapping("/usage")
    @Transactional
    public UsageView usage() {
        Clinic c = mine();
        MessageUsage u = outbound.monthUsage(c.getId());
        int pct = c.getMessageCap() == 0 ? 100 : (int) Math.round(100.0 * u.getBusinessInitiated() / c.getMessageCap());
        return new UsageView(u.getMonth(), u.getBusinessInitiated(), c.getMessageCap(), u.getServiceReplies(), u.getInbound(), pct);
    }
}
