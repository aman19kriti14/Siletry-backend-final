package com.siletry.messaging;

import com.siletry.appointment.Appointment;
import com.siletry.appointment.AppointmentRepository;
import com.siletry.appointment.AppointmentStatus;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.Language;
import com.siletry.common.Phone;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.messaging.OutboundMessageService.Outgoing;
import com.siletry.messaging.gateway.WhatsAppGateway.InboundMessage;
import com.siletry.messaging.gateway.WhatsAppGateway.StatusUpdate;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Every patient message lands here: dedupe, find/create patient, store, then
 * STOP handling → emergency check → staff-owned threads → bot.
 */
@Service
public class InboundMessageService {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageService.class);
    private static final Set<String> STOP_WORDS = Set.of("stop", "unsubscribe", "stop messages");

    private final ClinicRepository clinics;
    private final PatientService patients;
    private final MessageRepository messages;
    private final OutboundMessageService outbound;
    private final BotService bot;
    private final DoctorRepository doctors;
    private final AppointmentRepository appointments;

    public InboundMessageService(ClinicRepository clinics, PatientService patients, MessageRepository messages,
                                 OutboundMessageService outbound, BotService bot, DoctorRepository doctors,
                                 AppointmentRepository appointments) {
        this.clinics = clinics;
        this.patients = patients;
        this.messages = messages;
        this.outbound = outbound;
        this.bot = bot;
        this.doctors = doctors;
        this.appointments = appointments;
    }

    /** From the provider webhook: the clinic is identified by the number the patient wrote to. */
    @Transactional
    public Message handle(InboundMessage m) {
        if (m.externalId() != null && messages.existsByExternalId(m.externalId())) {
            log.debug("Duplicate webhook for {}", m.externalId());
            return null;
        }
        Optional<Clinic> clinic = m.accountId() != null ? clinics.findByWaPhoneNumberId(m.accountId()) : Optional.empty();
        if (clinic.isEmpty() && m.toNumber() != null) clinic = clinics.findByWhatsappNumber(Phone.normalize(m.toNumber()));
        if (clinic.isEmpty()) {
            log.warn("Inbound message to unknown number {} ({})", m.toNumber(), m.accountId());
            return null;
        }
        return handleForClinic(clinic.get(), m);
    }

    @Transactional
    public Message handleForClinic(Clinic clinic, InboundMessage m) {
        if (m.externalId() != null && messages.existsByExternalId(m.externalId())) return null;
        String text = m.text() == null ? "" : m.text().trim();

        Patient patient = patients.findOrCreate(clinic.getId(), m.fromPhone(), m.profileName(), true);
        Conversation conv = outbound.conversationFor(clinic.getId(), patient.getId());

        String lang = Language.detect(text);
        if (lang != null) {
            conv.setLanguage(lang);
            if (patient.getPreferredLanguage() == null) patient.setPreferredLanguage(lang);
        }

        Message in = new Message();
        in.setClinicId(clinic.getId());
        in.setConversationId(conv.getId());
        in.setPatientId(patient.getId());
        in.setDirection(Message.Direction.IN);
        in.setSender(Message.Sender.PATIENT);
        in.setKind(Message.Kind.TEXT);
        in.setBody(text);
        in.setExternalId(m.externalId());
        in.setStatus(Message.Status.RECEIVED);
        in.setLanguage(lang);
        in.setStatusAt(LocalDateTime.now());
        messages.save(in);

        LocalDateTime now = LocalDateTime.now();
        conv.setLastInboundAt(now);
        conv.setLastMessageAt(now);
        conv.setLastMessagePreview(OutboundMessageService.preview(text));
        conv.setUnreadCount(conv.getUnreadCount() + 1);
        outbound.countInbound(clinic.getId());

        // STOP
        if (STOP_WORDS.contains(text.toLowerCase(Locale.ROOT))) {
            patient.setOptedOut(true);
            outbound.send(clinic, patient, Outgoing.bot(MessageTexts.optedOut()));
            conv.setState(ConversationState.HANDLED);
            return in;
        }
        if (patient.isOptedOut()) patient.setOptedOut(false); // they wrote to us again

        // Emergency: reply instantly, never wait for staff
        Optional<EmergencyDetector.Kind> emergency = EmergencyDetector.detect(text);
        if (emergency.isPresent()) {
            handleEmergency(clinic, patient, conv, text, emergency.get());
            return in;
        }

        // Staff own this thread: bot stays quiet
        if (conv.isTakenOver()) {
            conv.setState(ConversationState.NEEDS_YOU);
            conv.setHandoffReason("New message while you're handling this chat");
            return in;
        }
        if (!clinic.isBotEnabled()) {
            conv.setState(ConversationState.NEEDS_YOU);
            conv.setHandoffReason(OutboundMessageService.preview(text));
            return in;
        }

        if (conv.getState() == ConversationState.HANDLED || conv.getState() == ConversationState.YOU_REPLIED) {
            conv.setState(ConversationState.BOT);
        }
        bot.handle(new BotService.Turn(clinic, patient, conv, text));
        return in;
    }

    private void handleEmergency(Clinic clinic, Patient patient, Conversation conv, String text, EmergencyDetector.Kind kind) {
        conv.setEmergency(true);
        conv.setState(ConversationState.NEEDS_YOU);
        conv.setHandoffReason((kind == EmergencyDetector.Kind.CRISIS ? "Crisis: " : "Emergency: ") + OutboundMessageService.preview(text));
        conv.setBotStep(null);
        conv.setBotContext(null);

        outbound.send(clinic, patient, Outgoing.bot(
                kind == EmergencyDetector.Kind.CRISIS ? MessageTexts.crisis() : MessageTexts.emergency()));

        // Flag today's visit, if any
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        List<Appointment> today = appointments.findInRange(clinic.getId(), dayStart, dayStart.plusDays(1));
        today.stream().filter(a -> a.getPatientId().equals(patient.getId()))
                .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED && a.getStatus() != AppointmentStatus.SEEN)
                .forEach(a -> a.setEmergency(true));

        // Alert every active doctor on their own phone
        String alert = "URGENT from " + clinic.getName() + ": " + patient.getName() + " (" + Phone.pretty(patient.getPhone())
                + ") wrote: \"" + OutboundMessageService.preview(text) + "\". Siletry told them to call 108. Please call them now.";
        for (Doctor d : doctors.findByClinicIdAndActiveTrueOrderByNameAsc(clinic.getId())) {
            outbound.alertStaff(d.getAlertMobile(), clinic.getName(), alert, null);
        }
        log.warn("Emergency message in clinic {} conversation {}", clinic.getId(), conv.getId());
    }

    @Transactional
    public void applyStatus(StatusUpdate s) {
        messages.findByExternalId(s.externalId()).ifPresent(m -> {
            if (rank(s.status()) > rank(m.getStatus()) || s.status() == Message.Status.FAILED) {
                m.setStatus(s.status());
                m.setStatusAt(LocalDateTime.now());
                if (s.error() != null) m.setError(s.error());
            }
        });
    }

    private int rank(Message.Status s) {
        return switch (s) {
            case QUEUED -> 0;
            case SENT -> 1;
            case DELIVERED -> 2;
            case READ -> 3;
            default -> -1;
        };
    }
}
