package com.siletry.dev;

import com.siletry.auth.CurrentUser;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.jobs.ScheduledJobs;
import com.siletry.messaging.*;
import com.siletry.messaging.InboxService.MessageView;
import com.siletry.messaging.gateway.GatewayRouter;
import com.siletry.messaging.gateway.WhatsAppGateway.InboundMessage;
import com.siletry.patient.PatientRepository;
import com.siletry.qr.QrCodeRepository;
import com.siletry.qr.QrService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mock WhatsApp panel: pretend to be a patient (or a doctor on the admin number)
 * and see exactly what Siletry replies. Only exists with DEV_TOOLS=true.
 * Everything here is mocked, even for clinics connected to real WhatsApp, so no real patient is ever messaged.
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "siletry.dev-tools", havingValue = "true")
public class DevController {

    public record PatientMessage(@NotBlank String phone, String name, @NotBlank String text) {}

    public record ScanRequest(@NotBlank String phone, String name, Long qrId, String code) {}

    public record AdminMessage(@NotBlank String phone, @NotBlank String text) {}

    public record SimResult(Long conversationId, List<MessageView> replies) {}

    private final ClinicRepository clinics;
    private final InboundMessageService inbound;
    private final InboxService inbox;
    private final AdminCommandService admin;
    private final PatientRepository patients;
    private final ConversationRepository conversations;
    private final QrCodeRepository qrs;
    private final ScheduledJobs jobs;

    public DevController(ClinicRepository clinics, InboundMessageService inbound, InboxService inbox,
                         AdminCommandService admin, PatientRepository patients, ConversationRepository conversations,
                         QrCodeRepository qrs, ScheduledJobs jobs) {
        this.clinics = clinics;
        this.inbound = inbound;
        this.inbox = inbox;
        this.admin = admin;
        this.patients = patients;
        this.conversations = conversations;
        this.qrs = qrs;
        this.jobs = jobs;
    }

    /** Send a message as a patient. Returns everything Siletry sent back. */
    @PostMapping("/whatsapp/patient-message")
    public SimResult patientMessage(@Valid @RequestBody PatientMessage req) {
        return simulate(req.phone(), req.name(), req.text());
    }

    /** Simulate scanning one of the clinic's QR codes. */
    @PostMapping("/whatsapp/scan")
    public SimResult scan(@Valid @RequestBody ScanRequest req) {
        Clinic c = clinic();
        var qr = req.qrId() != null
                ? qrs.findByIdAndClinicId(req.qrId(), c.getId())
                : qrs.findByClinicIdAndCode(c.getId(), req.code() == null ? "" : req.code().toUpperCase());
        var code = qr.orElseThrow(() -> ApiException.notFound("QR code"));
        return simulate(req.phone(), req.name(), QrService.prefilledText(c, code));
    }

    /** Send a message to the central Siletry number as a doctor/receptionist ("next", "queue"). */
    @PostMapping("/whatsapp/admin-message")
    public Map<String, String> adminMessage(@Valid @RequestBody AdminMessage req) {
        return Map.of("reply", GatewayRouter.simulate(() -> admin.handle(req.phone(), req.text())));
    }

    /** Full thread for a phone number, as the patient would see it. */
    @GetMapping("/whatsapp/thread")
    public List<MessageView> thread(@RequestParam String phone) {
        Clinic c = clinic();
        var p = patients.findByClinicIdAndPhone(c.getId(), Phone.normalize(phone)).orElseThrow(() -> ApiException.notFound("Patient"));
        var conv = conversations.findByClinicIdAndPatientId(c.getId(), p.getId()).orElseThrow(() -> ApiException.notFound("Conversation"));
        return inbox.after(conv.getId(), 0L);
    }

    @PostMapping("/jobs/reminders")
    public Map<String, Integer> runReminders() {
        return Map.of("sent", GatewayRouter.simulate(jobs::runReminders));
    }

    @PostMapping("/jobs/follow-ups")
    public Map<String, Integer> runFollowUps() {
        return Map.of("sent", GatewayRouter.simulate(jobs::runFollowUps));
    }

    private SimResult simulate(String phone, String name, String text) {
        Clinic c = clinic();
        Message in = GatewayRouter.simulate(() -> inbound.handleForClinic(c, new InboundMessage(c.getWhatsappNumber(), phone, name, text,
                "dev-" + UUID.randomUUID())));
        if (in == null) return new SimResult(null, List.of());
        List<MessageView> replies = inbox.after(in.getConversationId(), in.getId());
        return new SimResult(in.getConversationId(), replies);
    }

    private Clinic clinic() {
        return clinics.findById(CurrentUser.clinicId()).orElseThrow();
    }
}
