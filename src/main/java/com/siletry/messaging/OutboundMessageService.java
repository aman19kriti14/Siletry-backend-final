package com.siletry.messaging;

import com.siletry.clinic.Clinic;
import com.siletry.messaging.gateway.GatewayRouter;
import com.siletry.messaging.gateway.WhatsAppGateway;
import com.siletry.messaging.gateway.WhatsAppGateway.SendResult;
import com.siletry.messaging.gateway.WhatsAppGateway.TemplateMessage;
import com.siletry.patient.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * The one place that sends WhatsApp messages to patients.
 * Inside the 24-hour window: free text/buttons. Outside: the approved template, counted against the monthly cap.
 * Stores every message and updates the conversation.
 */
@Service
public class OutboundMessageService {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageService.class);

    private final GatewayRouter router;
    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final MessageUsageRepository usage;

    public OutboundMessageService(GatewayRouter router, MessageRepository messages,
                                  ConversationRepository conversations, MessageUsageRepository usage) {
        this.router = router;
        this.messages = messages;
        this.conversations = conversations;
        this.usage = usage;
    }

    /**
     * body = what the patient reads (stored and shown in the inbox).
     * template = what to send instead if the 24-hour window is closed (null = can't send outside the window).
     */
    public record Outgoing(String body, List<String> buttons, Message.Sender sender, Long userId, TemplateMessage template) {
        public static Outgoing bot(String body) { return new Outgoing(body, List.of(), Message.Sender.SILETRY, null, null); }
        public static Outgoing bot(String body, List<String> buttons) { return new Outgoing(body, buttons, Message.Sender.SILETRY, null, null); }
        public static Outgoing template(String body, List<String> buttons, TemplateMessage template) {
            return new Outgoing(body, buttons, Message.Sender.SILETRY, null, template);
        }
        public static Outgoing staff(String body, Long userId) { return new Outgoing(body, List.of(), Message.Sender.STAFF, userId, null); }
    }

    @Transactional
    public Conversation conversationFor(Long clinicId, Long patientId) {
        return conversations.findByClinicIdAndPatientId(clinicId, patientId).orElseGet(() -> {
            Conversation c = new Conversation();
            c.setClinicId(clinicId);
            c.setPatientId(patientId);
            c.setState(ConversationState.HANDLED);
            return conversations.save(c);
        });
    }

    @Transactional
    public Message send(Clinic clinic, Patient patient, Outgoing out) {
        Conversation conv = conversationFor(clinic.getId(), patient.getId());
        WhatsAppGateway gateway = router.forClinic(clinic);
        WhatsAppGateway.WaAccount from = router.accountFor(clinic);
        boolean window = conv.isWindowOpen();

        Message m = new Message();
        m.setClinicId(clinic.getId());
        m.setConversationId(conv.getId());
        m.setPatientId(patient.getId());
        m.setDirection(Message.Direction.OUT);
        m.setSender(out.sender());
        m.setBody(out.body());
        m.setSentByUserId(out.userId());
        m.setLanguage(patient.getPreferredLanguage());
        List<String> buttons = out.buttons() == null ? List.of() : out.buttons().stream().limit(10).toList();
        if (!buttons.isEmpty()) m.setButtons(String.join("\n", buttons));

        SendResult result;
        if (window) {
            m.setKind(buttons.isEmpty() ? Message.Kind.TEXT : Message.Kind.BUTTONS);
            result = buttons.isEmpty()
                    ? gateway.sendText(from, patient.getPhone(), out.body())
                    : gateway.sendButtons(from, patient.getPhone(), out.body(), buttons);
            if (result.ok()) bump(clinic.getId(), false);
        } else {
            m.setKind(Message.Kind.TEMPLATE);
            m.setBusinessInitiated(true);
            TemplateMessage t = out.template();
            m.setTemplateName(t == null ? null : t.name());
            if (t == null) {
                result = SendResult.failed("More than 24 hours since the patient's last message. Only approved templates can be sent now.");
            } else if (patient.isOptedOut()) {
                result = SendResult.failed("Patient opted out (sent STOP)");
            } else if (monthUsage(clinic.getId()).getBusinessInitiated() >= clinic.getMessageCap()) {
                result = SendResult.failed("Monthly message cap reached (" + clinic.getMessageCap() + ")");
            } else {
                result = gateway.sendTemplate(from, patient.getPhone(), t);
                if (result.ok()) bump(clinic.getId(), true);
            }
        }

        m.setExternalId(result.externalId());
        m.setStatus(result.ok() ? Message.Status.SENT : Message.Status.FAILED);
        m.setError(result.error());
        m.setStatusAt(LocalDateTime.now());
        messages.save(m);
        if (!result.ok()) log.warn("WhatsApp send to patient {} failed: {}", patient.getId(), result.error());

        conv.setLastMessageAt(LocalDateTime.now());
        conv.setLastMessagePreview(preview(out.body()));
        return m;
    }

    /**
     * Messages from Siletry's own number to doctors and staff.
     * Tries a normal message first; if WhatsApp refuses (no chat in 24 hours) it falls back to the template.
     */
    public SendResult sendAdmin(String toPhone, String body, List<String> buttons, TemplateMessage fallback) {
        if (toPhone == null || toPhone.isBlank()) return SendResult.failed("No phone number");
        WhatsAppGateway g = router.adminGateway();
        var from = router.adminAccount();
        SendResult r = buttons == null || buttons.isEmpty()
                ? g.sendText(from, toPhone, body)
                : g.sendButtons(from, toPhone, body, buttons);
        if (!r.ok() && fallback != null) r = g.sendTemplate(from, toPhone, fallback);
        if (!r.ok()) log.warn("Siletry message to {} failed: {}", toPhone, r.error());
        return r;
    }

    public SendResult sendAdmin(String toPhone, String body, List<String> buttons) {
        return sendAdmin(toPhone, body, buttons, null);
    }

    /** Doctor/staff alert with the staff-alert template as fallback. */
    public SendResult alertStaff(String toPhone, String clinicName, String body, List<String> buttons) {
        return sendAdmin(toPhone, body, buttons,
                TemplateMessage.of(TemplateCatalog.STAFF_ALERT.name(), clinicName, body));
    }

    /** Login codes: always the AUTHENTICATION template on the real number (the owner usually hasn't messaged us). */
    public SendResult sendLoginCode(String toPhone, String code, String fallbackText) {
        WhatsAppGateway g = router.adminGateway();
        if (!router.adminIsReal() || router.isSimulating()) return g.sendText(null, toPhone, fallbackText);
        SendResult r = g.sendTemplate(router.adminAccount(), toPhone,
                new TemplateMessage(TemplateCatalog.LOGIN_CODE, "en", List.of(code), code));
        if (!r.ok()) r = g.sendText(router.adminAccount(), toPhone, fallbackText);
        return r;
    }

    @Transactional
    public MessageUsage monthUsage(Long clinicId) {
        String month = YearMonth.now().toString();
        return usage.findByClinicIdAndMonth(clinicId, month).orElseGet(() -> {
            MessageUsage u = new MessageUsage();
            u.setClinicId(clinicId);
            u.setMonth(month);
            return usage.save(u);
        });
    }

    @Transactional
    public void countInbound(Long clinicId) {
        MessageUsage u = monthUsage(clinicId);
        u.setInbound(u.getInbound() + 1);
    }

    private void bump(Long clinicId, boolean businessInitiated) {
        MessageUsage u = monthUsage(clinicId);
        if (businessInitiated) u.setBusinessInitiated(u.getBusinessInitiated() + 1);
        else u.setServiceReplies(u.getServiceReplies() + 1);
    }

    public static String preview(String body) {
        if (body == null) return null;
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 140 ? oneLine.substring(0, 137) + "..." : oneLine;
    }
}
