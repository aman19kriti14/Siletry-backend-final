package com.siletry.e2e;

import com.siletry.appointment.Appointment;
import com.siletry.appointment.AppointmentRepository;
import com.siletry.appointment.AppointmentStatus;
import com.siletry.auth.AuthDtos.SignupRequest;
import com.siletry.auth.AuthService;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.CredentialCipher;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.doctor.DoctorSession;
import com.siletry.doctor.DoctorSessionRepository;
import com.siletry.jobs.ScheduledJobs;
import com.siletry.messaging.*;
import com.siletry.messaging.gateway.GupshupGateway;
import com.siletry.messaging.gateway.MetaCloudApiGateway;
import com.siletry.messaging.gateway.WhatsAppGateway.InboundMessage;
import com.siletry.messaging.gateway.WhatsAppGateway.SendResult;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A patient's whole journey against a real Postgres, with only the final WhatsApp HTTP calls replaced:
 * greeting, booking through the bot, reminders (sent once), emergency, STOP, duplicate webhooks,
 * a crashing bot, the Gupshup webhook, and the ops plan switch.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClinicFlowE2ETest {

    static final String OPS_KEY = "e2e-ops-key-0123456789abcdef";
    static final String GS_SECRET = "e2e-gupshup-secret-0123456789";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("siletry.seed-demo", () -> "false");
        r.add("siletry.ops.api-key", () -> OPS_KEY);
        r.add("siletry.gupshup.webhook-secret", () -> GS_SECRET);
    }

    @MockBean GupshupGateway gupshupGateway;
    @MockBean MetaCloudApiGateway metaGateway;
    @SpyBean BotService bot;

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ClinicRepository clinics;
    @Autowired DoctorRepository doctors;
    @Autowired DoctorSessionRepository sessions;
    @Autowired PatientRepository patients;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired AppointmentRepository appointments;
    @Autowired InboundMessageService inbound;
    @Autowired ScheduledJobs jobs;
    @Autowired CredentialCipher cipher;
    @Autowired TransactionTemplate tx;

    Clinic clinic;
    String patientPhone;

    @BeforeEach
    void setUp() {
        reset(bot, gupshupGateway, metaGateway);
        when(gupshupGateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.ok("gs-" + UUID.randomUUID()));
        when(gupshupGateway.sendButtons(any(), anyString(), anyString(), anyList())).thenReturn(SendResult.ok("gs-" + UUID.randomUUID()));
        when(gupshupGateway.sendTemplate(any(), anyString(), any())).thenReturn(SendResult.ok("gs-" + UUID.randomUUID()));

        String n = String.valueOf(System.nanoTime()).substring(4, 12);
        auth.signup(new SignupRequest("E2E Clinic " + n, "Jayanagar", "Bengaluru", "Dr Owner",
                "e2e" + n + "@example.com", "password123", "9180" + n, "9181" + n));
        clinic = clinics.findByWhatsappNumber("9181" + n).orElseThrow();
        patientPhone = "9198" + n;

        tx.executeWithoutResult(s -> {
            Clinic c = clinics.findById(clinic.getId()).orElseThrow();
            c.setReminderTime(LocalTime.MIDNIGHT); // reminders may go any time in this test
            Doctor d = new Doctor();
            d.setClinicId(c.getId());
            d.setName("Dr. Test Rao");
            d.setFee(500);
            d.setSlotMinutes(15);
            d.setMinNoticeMinutes(0);
            Doctor saved = doctors.save(d);
            for (DayOfWeek day : DayOfWeek.values()) {
                DoctorSession ds = new DoctorSession();
                ds.setClinicId(c.getId());
                ds.setDoctorId(saved.getId());
                ds.setDayOfWeek(day);
                ds.setStartTime(LocalTime.of(0, 0));
                ds.setEndTime(LocalTime.of(23, 45));
                sessions.save(ds);
            }
        });
        clinic = clinics.findById(clinic.getId()).orElseThrow();
    }

    // ------------------------------------------------------------------

    @Test
    void greetingGetsMenuButtons() {
        List<Message> replies = say("hi");
        assertThat(replies).isNotEmpty();
        assertThat(replies.get(replies.size() - 1).getButtons()).isNotBlank();
    }

    @Test
    void patientBooksThroughTheBotAndGetsOneReminder() {
        Appointment a = bookThroughBot();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);

        // Move it to tomorrow so the evening-before reminder applies
        tx.executeWithoutResult(s -> {
            Appointment x = appointments.findById(a.getId()).orElseThrow();
            LocalDateTime start = LocalDate.now().plusDays(1).atTime(10, 0);
            x.setStartAt(start);
            x.setEndAt(start.plusMinutes(15));
            x.setRemindEveningBefore(true);
            x.setReminderSentAt(null);
        });

        assertThat(jobs.runReminders()).isGreaterThanOrEqualTo(1);
        assertThat(appointments.findById(a.getId()).orElseThrow().getReminderSentAt()).isNotNull();
        int before = outCount();
        jobs.runReminders();
        assertThat(outCount()).as("second run must not re-send").isEqualTo(before);
    }

    @Test
    void emergencyGetsInstantReplyAndGoesToStaff() {
        List<Message> replies = say("my father has chest pain");
        assertThat(replies).isNotEmpty();
        Conversation c = conversation();
        assertThat(c.isEmergency()).isTrue();
        assertThat(c.getState()).isEqualTo(ConversationState.NEEDS_YOU);
    }

    @Test
    void stopOptsOutAndNextMessageOptsBackIn() {
        say("STOP");
        assertThat(patient().isOptedOut()).isTrue();
        say("hi");
        assertThat(patient().isOptedOut()).isFalse();
    }

    @Test
    void duplicateWebhookIsStoredOnce() {
        InboundMessage m = new InboundMessage(clinic.getWhatsappNumber(), patientPhone, "Pat", "hello there", "dup-" + UUID.randomUUID());
        assertThat(inbound.handleForClinic(clinic, m)).isNotNull();
        assertThat(inbound.handleForClinic(clinic, m)).isNull();
        long ins = messages.findAll().stream()
                .filter(x -> m.externalId().equals(x.getExternalId())).count();
        assertThat(ins).isEqualTo(1);
    }

    @Test
    void crashingBotHandsChatToStaffAndPatientHearsBack() {
        doThrow(new IllegalStateException("boom")).when(bot).handle(argThat(t -> "crash please".equals(t.text())));

        Message in = inbound.handleForClinic(clinic, new InboundMessage(clinic.getWhatsappNumber(), patientPhone, "Pat",
                "crash please", "crash-" + UUID.randomUUID()));

        assertThat(in).as("the patient's message is kept").isNotNull();
        assertThat(messages.findById(in.getId())).isPresent();
        Conversation c = conversation();
        assertThat(c.getState()).isEqualTo(ConversationState.NEEDS_YOU);
        assertThat(c.getHandoffReason()).contains("couldn't answer");
        List<Message> after = messages.findAfter(c.getId(), in.getId());
        assertThat(after).anyMatch(x -> x.getDirection() == Message.Direction.OUT
                && MessageTexts.handedOff().equals(x.getBody()));
    }

    @Test
    void gupshupWebhookReachesTheRightClinic() throws Exception {
        String appId = "gs-app-" + clinic.getId();
        tx.executeWithoutResult(s -> {
            Clinic c = clinics.findById(clinic.getId()).orElseThrow();
            c.setGupshupAppId(appId);
            c.setWaAccessToken(cipher.encrypt("sk_test_token"));
        });
        String body = """
                {"gs_app_id":"%s","object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages",
                 "value":{"metadata":{"display_phone_number":"%s","phone_number_id":"pn-x"},
                 "contacts":[{"wa_id":"%s","profile":{"name":"GS Patient"}}],
                 "messages":[{"from":"%s","id":"wamid.gs-%s","type":"text","text":{"body":"what are the fees"}}]}}]}]}
                """.formatted(appId, clinic.getWhatsappNumber(), patientPhone, patientPhone, UUID.randomUUID());

        mvc.perform(post("/api/webhooks/gupshup/not-the-secret-xxxxxxxxxxxx").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/gupshup/" + GS_SECRET).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(patients.findByClinicIdAndPhone(clinic.getId(), patientPhone)).isPresent();
        // The reply went out through Gupshup, not Meta
        verify(gupshupGateway, atLeastOnce()).sendText(argThat(a -> appId.equals(a.phoneNumberId())), eq(patientPhone), anyString());
        verifyNoInteractions(metaGateway);
    }

    @Test
    void opsCanMarkClinicPaidAndPause() throws Exception {
        mvc.perform(post("/api/ops/clinics/" + clinic.getId() + "/plan").header("X-Ops-Key", OPS_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plan").value("ACTIVE"));
        mvc.perform(post("/api/ops/clinics/" + clinic.getId() + "/plan").header("X-Ops-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PAUSED\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ops/clinics/" + clinic.getId() + "/plan").header("X-Ops-Key", OPS_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PAUSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.botEnabled").value(false));

        // Paused clinic: the bot stays quiet and the chat waits for staff
        List<Message> replies = say("I want to book an appointment");
        assertThat(replies).isEmpty();
        assertThat(conversation().getState()).isEqualTo(ConversationState.NEEDS_YOU);
    }

    // ------------------------------------------------------------------

    /** Sends a patient message and returns what Siletry sent back. */
    private List<Message> say(String text) {
        Clinic fresh = clinics.findById(clinic.getId()).orElseThrow();
        Message in = inbound.handleForClinic(fresh, new InboundMessage(fresh.getWhatsappNumber(), patientPhone, "Test Patient",
                text, "e2e-" + UUID.randomUUID()));
        assertThat(in).isNotNull();
        return messages.findAfter(in.getConversationId(), in.getId()).stream()
                .filter(m -> m.getDirection() == Message.Direction.OUT).toList();
    }

    /** Talks to the bot like a patient: always taps the first offered option until a visit is booked. */
    private Appointment bookThroughBot() {
        List<Message> replies = say("I want to book an appointment");
        for (int turn = 0; turn < 10; turn++) {
            Optional<Appointment> booked = upcoming();
            if (booked.isPresent()) return booked.get();
            assertThat(replies).as("bot replied on turn " + turn).isNotEmpty();
            Message last = replies.get(replies.size() - 1);
            String next = pick(last);
            replies = say(next);
        }
        return upcoming().orElseThrow(() -> new AssertionError("No appointment after 10 turns. Last reply: "
                + messages.findAll().stream().filter(m -> m.getDirection() == Message.Direction.OUT)
                .reduce((a, b) -> b).map(Message::getBody).orElse("none")));
    }

    private static String pick(Message m) {
        if (m.getButtons() == null || m.getButtons().isBlank()) return "Fever and cold";
        for (String b : m.getButtons().split("\n")) {
            String l = b.toLowerCase(Locale.ROOT);
            if (l.contains("talk") || l.contains("other") || l.contains("cancel") || l.contains("later")) continue;
            return b;
        }
        return m.getButtons().split("\n")[0];
    }

    private Optional<Appointment> upcoming() {
        Patient p = patients.findByClinicIdAndPhone(clinic.getId(), patientPhone).orElse(null);
        if (p == null) return Optional.empty();
        LocalDateTime from = LocalDate.now().atStartOfDay();
        return appointments.findInRange(clinic.getId(), from, from.plusDays(20)).stream()
                .filter(a -> a.getPatientId().equals(p.getId()) && a.getStatus() != AppointmentStatus.CANCELLED)
                .findFirst();
    }

    private Patient patient() {
        return patients.findByClinicIdAndPhone(clinic.getId(), patientPhone).orElseThrow();
    }

    private Conversation conversation() {
        return conversations.findByClinicIdAndPatientId(clinic.getId(), patient().getId()).orElseThrow();
    }

    private int outCount() {
        return (int) messages.findAll().stream()
                .filter(m -> clinic.getId().equals(m.getClinicId()) && m.getDirection() == Message.Direction.OUT).count();
    }
}
