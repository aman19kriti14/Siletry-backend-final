package com.siletry.seed;

import com.siletry.appointment.*;
import com.siletry.auth.AppUser;
import com.siletry.auth.AppUserRepository;
import com.siletry.auth.Role;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.clinic.ClinicSetupService;
import com.siletry.doctor.*;
import com.siletry.followup.FollowUp;
import com.siletry.followup.FollowUpRepository;
import com.siletry.messaging.*;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/**
 * First boot on an empty database: creates "Nandini Clinic" matching the designs, so every
 * screen has something real to show. Logins:
 *   owner:        demo@siletry.com / demo1234
 *   receptionist: desk@siletry.com / demo1234
 * Turn off with SEED_DEMO=false.
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final boolean enabled;
    private final TransactionTemplate tx;
    private final ClinicRepository clinics;
    private final ClinicSetupService setup;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final DoctorRepository doctors;
    private final DoctorSessionRepository sessions;
    private final DoctorLeaveRepository leaves;
    private final PatientRepository patients;
    private final AppointmentRepository appointments;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final FollowUpRepository followUps;

    private final Random rnd = new Random(42);
    private long patientNo = 40001;

    public DemoDataSeeder(@Value("${siletry.seed-demo}") boolean enabled, TransactionTemplate tx,
                          ClinicRepository clinics, ClinicSetupService setup, AppUserRepository users,
                          PasswordEncoder encoder, DoctorRepository doctors, DoctorSessionRepository sessions,
                          DoctorLeaveRepository leaves, PatientRepository patients, AppointmentRepository appointments,
                          ConversationRepository conversations, MessageRepository messages,
                          FollowUpRepository followUps) {
        this.enabled = enabled;
        this.tx = tx;
        this.clinics = clinics;
        this.setup = setup;
        this.users = users;
        this.encoder = encoder;
        this.doctors = doctors;
        this.sessions = sessions;
        this.leaves = leaves;
        this.patients = patients;
        this.appointments = appointments;
        this.conversations = conversations;
        this.messages = messages;
        this.followUps = followUps;
    }

    @Override
    public void run(String... args) {
        if (!enabled || clinics.count() > 0) return;
        tx.executeWithoutResult(s -> seed());
        log.info("Demo data created. Log in with demo@siletry.com / demo1234 (receptionist: desk@siletry.com / demo1234)");
    }

    private void seed() {
        LocalDate today = LocalDate.now();

        // ----- Clinic -----
        Clinic c = new Clinic();
        c.setName("Nandini Clinic");
        c.setArea("Jayanagar");
        c.setCity("Bengaluru");
        c.setAddress("No. 12, 9th Main Road, 4th Block, Jayanagar, Bengaluru 560011");
        c.setPhone("918047182200");
        c.setWhatsappNumber("918047182200");
        c.setWhatsappConnected(true);
        c.setClinicType("General practice");
        c.setLandmark("Above Apollo Pharmacy");
        c.setReplyLanguages("en,kn,hi");
        c.setDoctorCountHint(2);
        c.setWhatsappNumberChoice("CURRENT");
        c.setDelayAnnouncementsEnabled(true);
        c.setGoogleReviewsEnabled(false);
        c.setOnboardingStep(6);
        c.setLiveAt(LocalDateTime.now().minusDays(20));
        c = clinics.save(c);
        setup.createDefaults(c);
        Long cid = c.getId();

        // ----- Doctors -----
        Doctor rao = doctor(cid, "Dr. Anitha Rao", "General physician", "KMC 48213", 400, "919845000001", "en,kn,hi");
        Doctor menon = doctor(cid, "Dr. S. Menon", "Paediatrics", "KMC 51877", 500, "919845000003", "en,ml");

        for (DayOfWeek d : DayOfWeek.values()) {
            if (d != DayOfWeek.SUNDAY) session(cid, rao, d, "09:30", "13:00");
            // Evening every day except Wednesday (Sunday evening added so the demo works any day)
            if (d != DayOfWeek.WEDNESDAY) session(cid, rao, d, "16:30", "20:00");
            if (d != DayOfWeek.SUNDAY) {
                session(cid, menon, d, "10:00", "13:00");
                session(cid, menon, d, "17:00", "19:30");
            }
        }
        DoctorLeave leave = new DoctorLeave();
        leave.setClinicId(cid);
        leave.setDoctorId(menon.getId());
        leave.setStartAt(today.plusDays(2).atStartOfDay());
        leave.setEndAt(today.plusDays(3).atStartOfDay());
        leave.setReason("Conference");
        leaves.save(leave);

        // ----- Users -----
        user(cid, "Dr. Anitha Rao", "demo@siletry.com", "919845000001", Role.OWNER, rao.getId());
        user(cid, "Priya (Front desk)", "desk@siletry.com", "919845000002", Role.RECEPTIONIST, null);

        // ----- Patients from the designs -----
        Patient imran = patient(cid, "Imran Sheikh", "919845022114", 52, "Male", "en");
        Patient lakshmi = patient(cid, "Lakshmi Prasad", "919845012345", 34, "Female", "kn");
        lakshmi.setPreferredDoctorId(rao.getId());
        lakshmi.setEmergencyContactName("Suresh");
        lakshmi.setEmergencyContactPhone("919845055120");
        lakshmi.setOptInAt(LocalDateTime.of(2025, 3, 14, 10, 0));
        Patient rajesh = patient(cid, "ರಾಜೇಶ್ ಕುಮಾರ್", "919901277480", 61, "Male", "kn");
        Patient sunita = patient(cid, "सुनीता वर्मा", "919108830021", 29, "Female", "hi");
        Patient joseph = patient(cid, "Joseph Mathew", "919740055123", 45, "Male", "en");
        Patient fathima = patient(cid, "Fathima Beevi", "919632041007", 38, "Female", "en");
        Patient anand = patient(cid, "Anand Kulkarni", "919008460912", 57, "Male", "en");
        Patient meera = patient(cid, "Meera Nair", "919886210044", 26, "Female", "en");

        // ----- Past weeks, so charts and stats have data -----
        List<Patient> filler = new ArrayList<>();
        String[] names = {"Arjun Hegde", "Kavya Shetty", "Rohan Iyer", "Sneha Gowda", "Vikram Reddy", "Divya Bhat",
                "Mohammed Irfan", "Pooja Nayak", "Suresh Babu", "Ananya Rao", "Kiran Kumar", "Deepa Murthy",
                "Rahul Jain", "Shweta Patil", "Naveen Prakash", "Lata Krishnan", "Farhan Ali", "Ritu Sharma",
                "Ganesh Pai", "Nisha Menon", "ಮಂಜುನಾಥ್", "ಶ್ವೇತಾ", "राहुल गुप्ता", "प्रिया सिंह"};
        for (String n : names) {
            String lang = n.matches(".*[\\u0C80-\\u0CFF].*") ? "kn" : n.matches(".*[\\u0900-\\u097F].*") ? "hi"
                    : (rnd.nextInt(10) < 2 ? "kn" : "en");
            filler.add(patient(cid, n, "9199" + (10000000 + rnd.nextInt(89999999)), 18 + rnd.nextInt(60),
                    rnd.nextBoolean() ? "Female" : "Male", lang));
        }
        String[] reasons = {"Fever", "Cough and cold", "BP check-up", "Diabetes review", "Stomach pain", "Headache",
                "Skin rash", "Follow-up", "Back pain", "Vaccination", "Allergy", "Routine check-up"};
        for (int back = 13; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            for (Doctor d : List.of(rao, menon)) {
                List<LocalTime> times = slotTimes(d, day);
                Collections.shuffle(times, rnd);
                int n = Math.min(times.size(), d == rao ? 8 + rnd.nextInt(8) : 3 + rnd.nextInt(5));
                for (LocalTime t : times.subList(0, n)) {
                    Patient p = filler.get(rnd.nextInt(filler.size()));
                    AppointmentStatus st = rnd.nextInt(100) < (back > 6 ? 9 : 6) ? AppointmentStatus.NO_SHOW : AppointmentStatus.SEEN;
                    BookedBy by = rnd.nextInt(100) < 71 ? BookedBy.SILETRY : BookedBy.FRONT_DESK;
                    Appointment a = appt(cid, p, d, day.atTime(t), reasons[rnd.nextInt(reasons.length)], by, st);
                    a.setCreatedAt(day.atTime(t).minusHours(2 + rnd.nextInt(60)));
                    if (st == AppointmentStatus.SEEN) {
                        a.setCalledAt(day.atTime(t).plusMinutes(rnd.nextInt(10)));
                        a.setCompletedAt(a.getCalledAt().plusMinutes(6 + rnd.nextInt(8)));
                    }
                    if (by == BookedBy.SILETRY) botExchange(cid, p, a.getCreatedAt());
                }
            }
        }

        // ----- Lakshmi's history -----
        appt(cid, lakshmi, rao, today.minusDays(44).atTime(11, 0), "Viral fever", BookedBy.SILETRY, AppointmentStatus.SEEN);
        appt(cid, lakshmi, rao, today.minusDays(117).atTime(17, 0), "Routine check-up", BookedBy.SILETRY, AppointmentStatus.SEEN);
        appt(cid, lakshmi, rao, today.minusDays(220).atTime(18, 15), "Ankle sprain", BookedBy.FRONT_DESK, AppointmentStatus.SEEN);
        appt(cid, lakshmi, rao, today.minusDays(300).atTime(17, 30), "BP check-up", BookedBy.SILETRY, AppointmentStatus.NO_SHOW);
        appt(cid, lakshmi, rao, today.minusDays(400).atTime(10, 15), "Cough", BookedBy.SILETRY, AppointmentStatus.SEEN);
        appt(cid, lakshmi, rao, today.minusDays(500).atTime(19, 0), "Headache", BookedBy.FRONT_DESK, AppointmentStatus.SEEN);

        // ----- Today (matches the Appointments design) -----
        LocalDateTime now = LocalDateTime.now();
        Appointment a1 = appt(cid, imran, rao, today.atTime(16, 15), "Chest pain", BookedBy.WALK_IN, AppointmentStatus.WAITING);
        a1.setSlotKey(null);
        a1.setEmergency(true);
        queue(a1, 1, now.minusMinutes(20));
        Appointment a2 = appt(cid, lakshmi, rao, today.atTime(16, 30), "Fever, 3 days", BookedBy.SILETRY, AppointmentStatus.CONFIRMED);
        a2.setCreatedAt(today.atTime(11, 5));
        Appointment a3 = appt(cid, rajesh, menon, today.atTime(16, 45), "BP check-up", BookedBy.SILETRY, AppointmentStatus.WAITING);
        queue(a3, 2, now.minusMinutes(12));
        appt(cid, sunita, rao, today.atTime(17, 0), "Vaccination follow-up", BookedBy.SILETRY, AppointmentStatus.NO_SHOW).setSlotKey(null);
        appt(cid, joseph, menon, today.atTime(17, 15), "Dressing change", BookedBy.FRONT_DESK, AppointmentStatus.CONFIRMED);
        appt(cid, fathima, rao, today.atTime(17, 30), "Report reading", BookedBy.SILETRY, AppointmentStatus.CONFIRMED);
        appt(cid, anand, rao, today.atTime(17, 45), "Diabetes review", BookedBy.SILETRY, AppointmentStatus.CONFIRMED);
        appt(cid, meera, menon, today.atTime(18, 0), "Skin rash", BookedBy.SILETRY, AppointmentStatus.CONFIRMED);

        // A few bookings tomorrow so the slot grid shows booked slots
        LocalDate tomorrow = today.plusDays(1);
        List<LocalTime> tTimes = slotTimes(rao, tomorrow);
        for (int i = 0; i < Math.min(5, tTimes.size()); i += 2) {
            appt(cid, filler.get(i), rao, tomorrow.atTime(tTimes.get(i)), reasons[i], BookedBy.SILETRY, AppointmentStatus.CONFIRMED);
        }

        // ----- Follow-ups -----
        followUp(cid, lakshmi, rao, "BP review", today.plusDays(3));
        followUp(cid, lakshmi, rao, "Blood test", today.plusDays(48));

        // ----- Conversations (matches the Inbox design) -----
        Conversation ci = conv(cid, imran, ConversationState.NEEDS_YOU, "en", now.minusMinutes(25));
        ci.setEmergency(true);
        ci.setHandoffReason("Described chest pain");
        ci.setUnreadCount(3);
        msg(ci, Message.Direction.IN, Message.Sender.PATIENT, "Chest pain since morning, left side", now.minusMinutes(26));
        msg(ci, Message.Direction.OUT, Message.Sender.SILETRY, MessageTexts.emergency(), now.minusMinutes(26));
        msg(ci, Message.Direction.IN, Message.Sender.PATIENT, "I am coming to the clinic now", now.minusMinutes(25));

        Conversation ca = conv(cid, anand, ConversationState.NEEDS_YOU, "en", now.minusMinutes(40));
        ca.setHandoffReason("Asked about an insurance claim");
        ca.setUnreadCount(2);
        msg(ca, Message.Direction.IN, Message.Sender.PATIENT, "Does the clinic accept Star Health?", now.minusMinutes(41));
        msg(ca, Message.Direction.OUT, Message.Sender.SILETRY, MessageTexts.handedOff(), now.minusMinutes(41));

        Conversation cf = conv(cid, fathima, ConversationState.NEEDS_YOU, "en", now.minusMinutes(90));
        cf.setHandoffReason("Wants a report re-read");
        msg(cf, Message.Direction.IN, Message.Sender.PATIENT, "Can the doctor look at my blood report again? I have a doubt", now.minusMinutes(91));
        msg(cf, Message.Direction.OUT, Message.Sender.SILETRY, MessageTexts.handedOff(), now.minusMinutes(90));

        Conversation cj = conv(cid, joseph, ConversationState.NEEDS_YOU, "en", now.minusHours(3));
        cj.setHandoffReason("Third reschedule this week");
        msg(cj, Message.Direction.IN, Message.Sender.PATIENT, "Can I move my dressing change again? Something came up", now.minusHours(3));
        msg(cj, Message.Direction.OUT, Message.Sender.SILETRY, MessageTexts.handedOff(), now.minusHours(3));

        LocalDateTime lt = today.atTime(11, 4);
        Conversation cl = conv(cid, lakshmi, ConversationState.HANDLED, "kn", lt.plusMinutes(1));
        msg(cl, Message.Direction.IN, Message.Sender.PATIENT, "Doctor available today evening?", lt);
        Message offer = msg(cl, Message.Direction.OUT, Message.Sender.SILETRY,
                "Yes. Dr. Anitha Rao is seeing patients till 8 pm. 4:30 pm and 5:15 pm are free today.", lt);
        offer.setButtons("4:30 pm\n5:15 pm\nAnother day");
        offer.setKind(Message.Kind.BUTTONS);
        offer.setStatus(Message.Status.READ);
        msg(cl, Message.Direction.IN, Message.Sender.PATIENT, "4:30 ಸರಿ", lt.plusMinutes(1));
        msg(cl, Message.Direction.OUT, Message.Sender.SILETRY,
                "ಆಯಿತು. ಇಂದು ಸಂಜೆ 4:30ಕ್ಕೆ ಡಾ. ಅನಿತಾ ರಾವ್ ಅವರನ್ನು ಬುಕ್ ಮಾಡಲಾಗಿದೆ. ಬದಲಾಯಿಸಲು CHANGE, ರದ್ದುಗೊಳಿಸಲು CANCEL.", lt.plusMinutes(1));

        Conversation cr = conv(cid, rajesh, ConversationState.YOU_REPLIED, "kn", today.atTime(10, 12));
        msg(cr, Message.Direction.IN, Message.Sender.PATIENT, "ನಾಳೆ ಬರಬೇಕಾ?", today.atTime(10, 5));
        Message staff = msg(cr, Message.Direction.OUT, Message.Sender.STAFF, "Please carry your last BP chart.", today.atTime(10, 12));
        users.findByEmailIgnoreCase("desk@siletry.com").ifPresent(u -> staff.setSentByUserId(u.getId()));

        Conversation cs = conv(cid, sunita, ConversationState.HANDLED, "hi", today.atTime(9, 40));
        msg(cs, Message.Direction.IN, Message.Sender.PATIENT, "कल का टीका याद है?", today.atTime(9, 39));
        msg(cs, Message.Direction.OUT, Message.Sender.SILETRY, "हाँ, आपका टीका आज शाम 5:00 बजे डॉ. अनिता राव के साथ है।", today.atTime(9, 40));

    }

    // ---------------------------------------------------------------------

    private Doctor doctor(Long cid, String name, String spec, String reg, int fee, String alert, String langs) {
        Doctor d = new Doctor();
        d.setClinicId(cid);
        d.setName(name);
        d.setSpeciality(spec);
        d.setRegistrationNo(reg);
        d.setFee(fee);
        d.setAlertMobile(alert);
        d.setSlotMinutes(15);
        d.setLanguages(langs);
        return doctors.save(d);
    }

    private void session(Long cid, Doctor d, DayOfWeek day, String from, String to) {
        DoctorSession s = new DoctorSession();
        s.setClinicId(cid);
        s.setDoctorId(d.getId());
        s.setDayOfWeek(day);
        s.setStartTime(LocalTime.parse(from));
        s.setEndTime(LocalTime.parse(to));
        sessions.save(s);
    }

    private void user(Long cid, String name, String email, String phone, Role role, Long doctorId) {
        AppUser u = new AppUser();
        u.setClinicId(cid);
        u.setName(name);
        u.setEmail(email);
        u.setPhone(phone);
        u.setPasswordHash(encoder.encode("demo1234"));
        u.setRole(role);
        u.setDoctorId(doctorId);
        users.save(u);
    }

    private Patient patient(Long cid, String name, String phone, int age, String gender, String lang) {
        Patient p = new Patient();
        p.setClinicId(cid);
        p.setName(name);
        p.setPhone(phone);
        p.setPatientNo(patientNo++);
        p.setAge(age);
        p.setGender(gender);
        p.setPreferredLanguage(lang);
        p.setWhatsappOptIn(true);
        p.setOptInAt(LocalDateTime.now().minusDays(30 + rnd.nextInt(300)));
        p.setCreatedAt(p.getOptInAt());
        Patient saved = patients.save(p);
        clinics.findById(cid).ifPresent(c -> c.setNextPatientNo(patientNo));
        return saved;
    }

    private Appointment appt(Long cid, Patient p, Doctor d, LocalDateTime start, String reason, BookedBy by, AppointmentStatus st) {
        Appointment a = new Appointment();
        a.setClinicId(cid);
        a.setPatientId(p.getId());
        a.setDoctorId(d.getId());
        a.setStartAt(start);
        a.setEndAt(start.plusMinutes(d.getSlotMinutes()));
        a.setDurationMinutes(d.getSlotMinutes());
        a.setReason(reason);
        a.setBookedBy(by);
        a.setSource(by == BookedBy.SILETRY ? "whatsapp" : "web");
        a.setStatus(st);
        a.setFeeAtBooking(d.getFee());
        a.setCreatedAt(start.minusDays(1));
        if (st.occupiesSlot()) a.setSlotKey(Appointment.slotKeyFor(d.getId(), start));
        return appointments.save(a);
    }

    private void queue(Appointment a, int token, LocalDateTime checkedIn) {
        a.setTokenNumber(token);
        a.setQueueOrder((long) token);
        a.setCheckedInAt(checkedIn);
    }

    private List<LocalTime> slotTimes(Doctor d, LocalDate day) {
        List<LocalTime> out = new ArrayList<>();
        for (DoctorSession s : sessions.findByDoctorIdAndDayOfWeekOrderByStartTimeAsc(d.getId(), day.getDayOfWeek())) {
            for (LocalTime t = s.getStartTime(); !t.plusMinutes(d.getSlotMinutes()).isAfter(s.getEndTime()); t = t.plusMinutes(d.getSlotMinutes())) {
                out.add(t);
            }
        }
        return out;
    }

    private void followUp(Long cid, Patient p, Doctor d, String title, LocalDate due) {
        FollowUp f = new FollowUp();
        f.setClinicId(cid);
        f.setPatientId(p.getId());
        f.setDoctorId(d.getId());
        f.setTitle(title);
        f.setDueDate(due);
        followUps.save(f);
    }

    private Conversation conv(Long cid, Patient p, ConversationState state, String lang, LocalDateTime last) {
        Conversation c = conversations.findByClinicIdAndPatientId(cid, p.getId()).orElseGet(Conversation::new);
        c.setClinicId(cid);
        c.setPatientId(p.getId());
        c.setState(state);
        c.setLanguage(lang);
        if (c.getLastMessageAt() == null || last.isAfter(c.getLastMessageAt())) c.setLastMessageAt(last);
        if (c.getLastInboundAt() == null || last.isAfter(c.getLastInboundAt())) c.setLastInboundAt(last);
        return conversations.save(c);
    }

    private Message msg(Conversation c, Message.Direction dir, Message.Sender sender, String body, LocalDateTime at) {
        Message m = new Message();
        m.setClinicId(c.getClinicId());
        m.setConversationId(c.getId());
        m.setPatientId(c.getPatientId());
        m.setDirection(dir);
        m.setSender(sender);
        m.setKind(Message.Kind.TEXT);
        m.setBody(body);
        m.setStatus(dir == Message.Direction.IN ? Message.Status.RECEIVED : Message.Status.READ);
        m.setCreatedAt(at);
        m.setStatusAt(at);
        c.setLastMessagePreview(OutboundMessageService.preview(body));
        return messages.save(m);
    }

    /** A short bot booking exchange, so "Messages answered" has real rows behind it. */
    private void botExchange(Long cid, Patient p, LocalDateTime at) {
        Conversation c = conv(cid, p, ConversationState.HANDLED, p.getPreferredLanguage(), at.plusMinutes(2));
        msg(c, Message.Direction.IN, Message.Sender.PATIENT, "I want to book an appointment", at);
        msg(c, Message.Direction.OUT, Message.Sender.SILETRY, "Which day works for you?", at);
        msg(c, Message.Direction.IN, Message.Sender.PATIENT, "Tomorrow evening", at.plusMinutes(1));
        msg(c, Message.Direction.OUT, Message.Sender.SILETRY, "Booked. See you then.", at.plusMinutes(2));
    }
}
