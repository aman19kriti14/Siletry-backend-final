package com.siletry.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siletry.appointment.*;
import com.siletry.appointment.BookingService.BookCommand;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.ApiException;
import com.siletry.common.Fmt;
import com.siletry.doctor.*;
import com.siletry.followup.FollowUp;
import com.siletry.messaging.OutboundMessageService.Outgoing;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientRepository;
import com.siletry.qr.QrCode;
import com.siletry.qr.QrService;
import com.siletry.queue.QueueDtos.QueueEntry;
import com.siletry.queue.QueueDtos.QueueView;
import com.siletry.queue.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rule-based WhatsApp receptionist for v1: booking with buttons, check-in, cancel/reschedule,
 * reminder replies, fees/hours/address, and handoff to staff for anything else.
 * The AI layer can replace handoff() later without touching the flows.
 */
@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    static final String STEP_PICK_DOCTOR = "PICK_DOCTOR";
    static final String STEP_PICK_DAY = "PICK_DAY";
    static final String STEP_PICK_SLOT = "PICK_SLOT";
    static final String STEP_ASK_REASON = "ASK_REASON";
    static final String STEP_CONFIRM_CANCEL = "CONFIRM_CANCEL";

    private static final int HOLD_MINUTES = 10;
    private static final int DEFAULT_STEP_HOURS = 2;

    private static final Pattern BOOK = words("book", "booking", "appointment", "appt", "slot", "slots", "available",
            "availability", "consult", "consultation", "visit", "see the doctor", "see doctor", "meet the doctor");
    private static final List<String> BOOK_INDIC = List.of("ಬುಕ್", "ಅಪಾಯಿಂಟ್ಮೆಂಟ್", "बुक", "अपॉइंटमेंट", "मिलना");
    private static final Pattern CHECK_IN = words("check in", "checkin", "check-in", "i'm here", "i am here", "im here",
            "reached", "arrived", "at the clinic", "i'm at the clinic");
    private static final Pattern FEES = words("fee", "fees", "charge", "charges", "cost", "price", "kitna");
    private static final Pattern HOURS = words("timing", "timings", "hours", "open", "opening", "close", "closing", "kab tak");
    private static final Pattern ADDRESS = words("address", "location", "where is", "directions", "map", "route");
    private static final Pattern GREETING = words("hi", "hii", "hello", "hey", "namaste", "namaskara", "namaskar",
            "good morning", "good afternoon", "good evening", "hlo");
    private static final Pattern THANKS = words("thank you", "thanks", "thx", "thank u", "dhanyavad", "dhanyavaad", "shukriya");
    private static final Pattern YES = words("yes", "y", "yeah", "yep", "ok", "okay", "confirm", "confirmed", "haan", "ha", "houdu", "sari");
    private static final Pattern NO = words("no", "nope", "keep", "keep it", "don't cancel", "dont cancel");
    private static final Pattern CANCEL = words("cancel", "cancel it", "cancel appointment");
    private static final Pattern CHANGE = words("change", "reschedule", "postpone", "another time", "different time");
    private static final Pattern TODAY = words("today", "tonight", "aaj", "indu");
    private static final Pattern TOMORROW = words("tomorrow", "tmrw", "tmr", "kal", "naale", "nale");
    private static final Pattern MORNING = words("morning", "subah", "belige");
    private static final Pattern AFTERNOON = words("afternoon", "dopahar", "madhyahna");
    private static final Pattern EVENING = words("evening", "tonight", "night", "shaam", "sham", "sanje");

    private final DoctorRepository doctors;
    private final DoctorSessionRepository sessions;
    private final SlotService slots;
    private final BookingService booking;
    private final QueueService queue;
    private final AppointmentRepository appointments;
    private final OutboundMessageService outbound;
    private final QrService qr;
    private final ClinicRepository clinics;
    private final PatientRepository patients;
    private final com.siletry.followup.FollowUpRepository followUps;
    private final ObjectMapper mapper;

    public BotService(DoctorRepository doctors, DoctorSessionRepository sessions, SlotService slots,
                      BookingService booking, QueueService queue, AppointmentRepository appointments,
                      OutboundMessageService outbound, QrService qr, ClinicRepository clinics,
                      PatientRepository patients, com.siletry.followup.FollowUpRepository followUps,
                      ObjectMapper mapper) {
        this.doctors = doctors;
        this.sessions = sessions;
        this.slots = slots;
        this.booking = booking;
        this.queue = queue;
        this.appointments = appointments;
        this.outbound = outbound;
        this.qr = qr;
        this.clinics = clinics;
        this.patients = patients;
        this.followUps = followUps;
        this.mapper = mapper;
    }

    public record Turn(Clinic clinic, Patient patient, Conversation conv, String text) {}

    // =====================================================================
    // Entry point
    // =====================================================================

    @Transactional
    public void handle(Turn t) {
        String text = t.text() == null ? "" : t.text().trim();
        String lower = text.toLowerCase(Locale.ROOT);
        expireStaleStep(t.conv());

        // 1. QR scan (prefilled text carries the code)
        String code = QrService.extractCode(text);
        if (code != null) {
            QrCode q = qr.recordScan(t.clinic().getId(), code);
            clearStep(t.conv());
            if (code.startsWith("CHECKIN")) {
                checkIn(t, code);
            } else {
                startBooking(t, code, null, q == null ? null : q.getDoctorId(), lower);
            }
            return;
        }

        // 2. Continue a pending step
        if (t.conv().getBotStep() != null && continueStep(t, text, lower)) return;

        // 3. Intents
        if (YES.matcher(lower).matches() && confirmReminder(t)) return;
        if (CANCEL.matcher(lower).find() || text.equalsIgnoreCase(MessageTexts.BTN_CANCEL)) { askCancel(t); return; }
        if (CHANGE.matcher(lower).find()) { startReschedule(t); return; }
        if (text.equalsIgnoreCase(MessageTexts.BTN_CHECK_IN) || CHECK_IN.matcher(lower).find()) { checkIn(t, "whatsapp"); return; }
        if (text.equalsIgnoreCase(MessageTexts.BTN_TALK)) { handoff(t, "Asked to talk to the clinic"); return; }
        if (FEES.matcher(lower).find()) { replyFees(t); return; }
        if (HOURS.matcher(lower).find() && !BOOK.matcher(lower).find()) { replyHours(t); return; }
        if (ADDRESS.matcher(lower).find()) { replyAddress(t); return; }
        if (text.equalsIgnoreCase(MessageTexts.BTN_BOOK) || BOOK.matcher(lower).find()
                || BOOK_INDIC.stream().anyMatch(text::contains)
                || ((TODAY.matcher(lower).find() || TOMORROW.matcher(lower).find()) && lower.contains("doctor"))) {
            startBooking(t, "whatsapp", null, null, lower);
            return;
        }
        if (THANKS.matcher(lower).find() && lower.split("\\s+").length <= 5) {
            reply(t, "You're welcome! Take care.");
            resolved(t);
            return;
        }
        if (GREETING.matcher(lower).find() && lower.split("\\s+").length <= 4) {
            reply(t, MessageTexts.greeting(t.clinic()),
                    List.of(MessageTexts.BTN_BOOK, MessageTexts.BTN_CHECK_IN, MessageTexts.BTN_TALK));
            return;
        }

        // 4. Anything else goes to a human
        handoff(t, summarize(text));
    }

    // =====================================================================
    // Pending steps
    // =====================================================================

    private boolean continueStep(Turn t, String text, String lower) {
        Map<String, Object> ctx = ctx(t.conv());
        String step = t.conv().getBotStep();
        Map<String, String> options = options(ctx);

        switch (step) {
            case STEP_PICK_DOCTOR -> {
                String picked = matchOption(text, options);
                if (picked == null) return false;
                Doctor d = doctors.findById(Long.valueOf(picked)).orElse(null);
                if (d == null) return false;
                ctx.put("doctorId", d.getId());
                if ("CHECKIN".equals(ctx.get("purpose"))) {
                    clearStep(t.conv());
                    walkIn(t, d, (String) ctx.get("source"));
                } else {
                    continueBookingWithDoctor(t, ctx, d);
                }
                return true;
            }
            case STEP_PICK_DAY -> {
                Doctor d = doctorFromCtx(ctx);
                if (d == null) return false;
                LocalDate date = null;
                String picked = matchOption(text, options);
                if (picked != null) date = LocalDate.parse(picked);
                else if (TODAY.matcher(lower).find()) date = LocalDate.now();
                else if (TOMORROW.matcher(lower).find()) date = LocalDate.now().plusDays(1);
                if (date == null) return false;
                offerSlots(t, ctx, d, date, period(lower));
                return true;
            }
            case STEP_PICK_SLOT -> {
                Doctor d = doctorFromCtx(ctx);
                if (d == null) return false;
                if (text.equalsIgnoreCase(MessageTexts.BTN_ANOTHER_DAY) || lower.contains("another day") || lower.contains("other day")) {
                    offerDays(t, ctx, d);
                    return true;
                }
                String picked = matchOption(text, options);
                if (picked != null) {
                    onSlotPicked(t, ctx, d, LocalDateTime.parse(picked));
                    return true;
                }
                if (TODAY.matcher(lower).find()) { offerSlots(t, ctx, d, LocalDate.now(), period(lower)); return true; }
                if (TOMORROW.matcher(lower).find()) { offerSlots(t, ctx, d, LocalDate.now().plusDays(1), period(lower)); return true; }
                return false;
            }
            case STEP_ASK_REASON -> {
                clearStep(t.conv());
                boolean isCommand = CANCEL.matcher(lower).find() || CHANGE.matcher(lower).find()
                        || BOOK.matcher(lower).find() || CHECK_IN.matcher(lower).find();
                if (isCommand || text.length() < 2) return false;
                Object apptId = ctx.get("appointmentId");
                if (apptId != null) {
                    appointments.findById(((Number) apptId).longValue()).ifPresent(a -> {
                        String reason = text.length() > 500 ? text.substring(0, 500) : text;
                        a.setReason(reason);
                    });
                }
                reply(t, MessageTexts.reasonNoted());
                resolved(t);
                return true;
            }
            case STEP_CONFIRM_CANCEL -> {
                Object apptId = ctx.get("appointmentId");
                boolean yes = text.equalsIgnoreCase(MessageTexts.BTN_YES_CANCEL) || YES.matcher(lower).matches()
                        || CANCEL.matcher(lower).find();
                boolean no = text.equalsIgnoreCase(MessageTexts.BTN_KEEP) || NO.matcher(lower).find();
                if (!yes && !no) return false;
                clearStep(t.conv());
                if (!no && apptId != null) { // "don't cancel" counts as no
                    Appointment a = booking.cancel(t.clinic().getId(), ((Number) apptId).longValue(), "Cancelled by patient on WhatsApp");
                    Doctor d = doctors.findById(a.getDoctorId()).orElseThrow();
                    reply(t, MessageTexts.cancelled(d, a.getStartAt()));
                } else {
                    reply(t, MessageTexts.keptAppointment());
                }
                resolved(t);
                return true;
            }
            default -> {
                clearStep(t.conv());
                return false;
            }
        }
    }

    // =====================================================================
    // Booking
    // =====================================================================

    private void startBooking(Turn t, String source, Long rescheduleOf, Long doctorHint, String lower) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("purpose", "BOOK");
        ctx.put("source", source);
        if (rescheduleOf != null) ctx.put("rescheduleOf", rescheduleOf);
        LocalDate dayHint = TODAY.matcher(lower).find() ? LocalDate.now()
                : TOMORROW.matcher(lower).find() ? LocalDate.now().plusDays(1) : null;
        if (dayHint != null) ctx.put("dayHint", dayHint.toString());
        String period = period(lower);
        if (period != null) ctx.put("period", period);

        List<Doctor> list = bookableDoctors(t.clinic().getId());
        if (doctorHint != null) {
            list.stream().filter(d -> d.getId().equals(doctorHint)).findFirst()
                    .ifPresentOrElse(d -> continueBookingWithDoctor(t, ctx, d), () -> askDoctor(t, ctx, list));
            return;
        }
        if (list.isEmpty()) {
            handoff(t, "Wants to book, but no doctor is open for WhatsApp booking");
            return;
        }
        if (list.size() == 1) {
            continueBookingWithDoctor(t, ctx, list.get(0));
            return;
        }
        askDoctor(t, ctx, list);
    }

    private void askDoctor(Turn t, Map<String, Object> ctx, List<Doctor> list) {
        if (list.isEmpty()) {
            handoff(t, "Wants to book, but no doctor is open for WhatsApp booking");
            return;
        }
        Long preferred = t.patient().getPreferredDoctorId();
        List<Doctor> ordered = new ArrayList<>(list);
        ordered.sort(Comparator.comparing((Doctor d) -> !d.getId().equals(preferred)).thenComparing(Doctor::getName));
        Map<String, String> opts = new LinkedHashMap<>();
        for (Doctor d : ordered.subList(0, Math.min(ordered.size(), 10))) {
            String label = d.getSpeciality() == null ? d.shortName() : d.shortName() + " (" + d.getSpeciality() + ")";
            opts.put(label.length() > 24 ? d.shortName() : label, String.valueOf(d.getId()));
        }
        ctx.put("options", opts);
        setStep(t.conv(), STEP_PICK_DOCTOR, ctx);
        reply(t, MessageTexts.pickDoctor(), new ArrayList<>(opts.keySet()));
    }

    private void continueBookingWithDoctor(Turn t, Map<String, Object> ctx, Doctor d) {
        ctx.put("doctorId", d.getId());
        Object dayHint = ctx.remove("dayHint");
        if (dayHint != null) {
            offerSlots(t, ctx, d, LocalDate.parse(dayHint.toString()), (String) ctx.get("period"));
        } else {
            offerDays(t, ctx, d);
        }
    }

    private void offerDays(Turn t, Map<String, Object> ctx, Doctor d) {
        int horizon = Math.min(d.getMaxAdvanceDays(), 14);
        List<SlotService.DaySummary> days = slots.daySummaries(d, LocalDate.now(), horizon, true).stream()
                .filter(s -> s.free() > 0).limit(6).toList();
        if (days.isEmpty()) {
            reply(t, MessageTexts.noDays(d));
            markNeedsYou(t, "Wanted to book " + d.shortName() + " but no free slots");
            clearStep(t.conv());
            return;
        }
        Map<String, String> opts = new LinkedHashMap<>();
        days.forEach(s -> opts.put(s.label(), s.date().toString()));
        ctx.put("options", opts);
        ctx.put("doctorId", d.getId());
        setStep(t.conv(), STEP_PICK_DAY, ctx);
        reply(t, MessageTexts.pickDay(d), new ArrayList<>(opts.keySet()));
    }

    private void offerSlots(Turn t, Map<String, Object> ctx, Doctor d, LocalDate date, String period) {
        List<LocalDateTime> free = slots.freeSlots(d, date, true);
        List<LocalDateTime> inPeriod = filterPeriod(free, period);
        if (!inPeriod.isEmpty()) free = inPeriod;
        if (free.isEmpty()) {
            reply(t, MessageTexts.noSlotsThatDay(d, Fmt.relativeDay(date)));
            offerDays(t, ctx, d);
            return;
        }
        List<LocalDateTime> picks = new ArrayList<>();
        picks.add(free.get(0));
        if (free.size() > 1) picks.add(free.get(Math.min(3, free.size() - 1)));

        List<LocalDateTime> held = slots.hold(t.clinic().getId(), d.getId(), picks, holderRef(t.conv()), HOLD_MINUTES);
        if (held.isEmpty()) held = picks; // held by someone else in a race: still offer, booking re-validates

        Map<String, String> opts = new LinkedHashMap<>();
        held.forEach(s -> opts.put(Fmt.time(s), s.toString()));
        ctx.put("options", opts);
        ctx.put("doctorId", d.getId());
        ctx.put("date", date.toString());
        setStep(t.conv(), STEP_PICK_SLOT, ctx);

        String until = date.equals(LocalDate.now()) ? lastSessionEnd(d, date) : null;
        List<String> labels = new ArrayList<>(opts.keySet());
        String body = MessageTexts.offerSlots(d, Fmt.relativeDay(date), labels.get(0),
                labels.size() > 1 ? labels.get(1) : null, until);
        List<String> buttons = new ArrayList<>(labels);
        buttons.add(MessageTexts.BTN_ANOTHER_DAY);
        reply(t, body, buttons);
    }

    private void onSlotPicked(Turn t, Map<String, Object> ctx, Doctor d, LocalDateTime start) {
        Long clinicId = t.clinic().getId();
        String holder = holderRef(t.conv());
        Object rescheduleOf = ctx.get("rescheduleOf");
        try {
            if (rescheduleOf != null) {
                Appointment a = booking.reschedule(clinicId, ((Number) rescheduleOf).longValue(), start, d.getId(), holder, true);
                clearStep(t.conv());
                reply(t, MessageTexts.rescheduled(t.clinic(), d, a.getStartAt()));
                resolved(t);
                return;
            }
            Long followUpId = ctx.get("followUpId") == null ? null : ((Number) ctx.get("followUpId")).longValue();
            String reason = (String) ctx.get("reason");
            Appointment a = booking.book(new BookCommand(clinicId, t.patient().getId(), d.getId(), start, null,
                    reason, null, BookedBy.SILETRY, (String) ctx.get("source"), true, holder, null, followUpId), true);
            if (t.patient().getPreferredDoctorId() == null) t.patient().setPreferredDoctorId(d.getId());
            reply(t, MessageTexts.booked(t.clinic(), d, a.getStartAt()));
            if (reason == null) {
                Map<String, Object> next = new LinkedHashMap<>();
                next.put("appointmentId", a.getId());
                setStep(t.conv(), STEP_ASK_REASON, next);
                reply(t, MessageTexts.askReason());
            } else {
                clearStep(t.conv());
                resolved(t);
            }
        } catch (ApiException e) {
            log.info("Bot booking failed for conv {}: {}", t.conv().getId(), e.getMessage());
            reply(t, MessageTexts.slotTaken());
            offerSlots(t, ctx, d, start.toLocalDate(), null);
        }
    }

    // =====================================================================
    // Check-in / queue
    // =====================================================================

    private void checkIn(Turn t, String source) {
        Long clinicId = t.clinic().getId();
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        List<Appointment> today = appointments.findInRange(clinicId, dayStart, dayStart.plusDays(1)).stream()
                .filter(a -> a.getPatientId().equals(t.patient().getId()))
                .toList();

        Optional<Appointment> inQueue = today.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.WAITING || a.getStatus() == AppointmentStatus.IN_CONSULT)
                .findFirst();
        if (inQueue.isPresent()) {
            Appointment a = inQueue.get();
            if (a.getStatus() == AppointmentStatus.IN_CONSULT) {
                reply(t, "You're being seen now. Please go in if you haven't already.");
            } else {
                QueueView q = queue.view(clinicId, a.getDoctorId());
                QueueEntry e = q.waiting().stream().filter(x -> x.appointmentId().equals(a.getId())).findFirst().orElse(null);
                int ahead = e == null ? 0 : e.position() - 1;
                int eta = e == null ? 0 : e.etaMinutes();
                reply(t, MessageTexts.checkedIn(a.getTokenNumber(), ahead, eta));
            }
            return;
        }

        Optional<Appointment> booked = today.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.CONFIRMED)
                .min(Comparator.comparing(Appointment::getStartAt));
        if (booked.isPresent()) {
            queue.checkIn(clinicId, booked.get().getId()); // sends the token message
            resolved(t);
            return;
        }

        List<Doctor> working = doctorsWorkingNow(clinicId);
        if (working.isEmpty()) {
            reply(t, MessageTexts.noDoctorNow(), List.of(MessageTexts.BTN_BOOK));
            return;
        }
        if (working.size() == 1) {
            walkIn(t, working.get(0), source);
            return;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("purpose", "CHECKIN");
        ctx.put("source", source);
        Map<String, String> opts = new LinkedHashMap<>();
        working.stream().limit(10).forEach(d -> opts.put(d.shortName(), String.valueOf(d.getId())));
        ctx.put("options", opts);
        setStep(t.conv(), STEP_PICK_DOCTOR, ctx);
        reply(t, "Welcome! Which doctor are you here to see?", new ArrayList<>(opts.keySet()));
    }

    private void walkIn(Turn t, Doctor d, String source) {
        queue.walkIn(t.clinic().getId(), t.patient().getId(), d.getId(), null, false, BookedBy.WALK_IN,
                source == null ? "whatsapp" : source, null); // sends the token message
        resolved(t);
    }

    // =====================================================================
    // Cancel / reschedule / reminder replies
    // =====================================================================

    private Optional<Appointment> nextUpcoming(Turn t) {
        return appointments.findUpcomingForPatient(t.clinic().getId(), t.patient().getId(), LocalDateTime.now())
                .stream().findFirst();
    }

    private void askCancel(Turn t) {
        Optional<Appointment> next = nextUpcoming(t);
        if (next.isEmpty()) {
            reply(t, MessageTexts.noUpcoming(), List.of(MessageTexts.BTN_BOOK));
            return;
        }
        Appointment a = next.get();
        if (a.getReminderSentAt() != null && a.getReminderResponse() == null) a.setReminderResponse("CANCEL");
        Doctor d = doctors.findById(a.getDoctorId()).orElseThrow();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("appointmentId", a.getId());
        setStep(t.conv(), STEP_CONFIRM_CANCEL, ctx);
        reply(t, MessageTexts.confirmCancel(d, a.getStartAt()), List.of(MessageTexts.BTN_YES_CANCEL, MessageTexts.BTN_KEEP));
    }

    private void startReschedule(Turn t) {
        Optional<Appointment> next = nextUpcoming(t);
        if (next.isEmpty()) {
            reply(t, MessageTexts.noUpcoming(), List.of(MessageTexts.BTN_BOOK));
            return;
        }
        Appointment a = next.get();
        if (a.getReminderSentAt() != null && a.getReminderResponse() == null) a.setReminderResponse("CHANGE");
        startBooking(t, "whatsapp", a.getId(), a.getDoctorId(), "");
    }

    private boolean confirmReminder(Turn t) {
        Optional<Appointment> next = nextUpcoming(t)
                .filter(a -> a.getReminderSentAt() != null && a.getReminderResponse() == null);
        if (next.isEmpty()) return false;
        next.get().setReminderResponse("YES");
        reply(t, MessageTexts.reminderConfirmed());
        resolved(t);
        return true;
    }

    // =====================================================================
    // FAQs
    // =====================================================================

    private void replyFees(Turn t) {
        List<Doctor> list = doctors.findByClinicIdAndActiveTrueOrderByNameAsc(t.clinic().getId());
        if (list.isEmpty() || list.stream().allMatch(d -> d.getFee() == 0)) {
            handoff(t, "Asked about fees");
            return;
        }
        String body = "Consultation fees: " + list.stream()
                .map(d -> d.displayName() + " ₹" + d.getFee()).collect(Collectors.joining(", ")) + ".";
        reply(t, body, List.of(MessageTexts.BTN_BOOK));
    }

    private void replyHours(Turn t) {
        LocalDate today = LocalDate.now();
        List<Doctor> list = doctors.findByClinicIdAndActiveTrueOrderByNameAsc(t.clinic().getId());
        StringBuilder sb = new StringBuilder("Today's timings:\n");
        for (Doctor d : list) {
            List<DoctorSession> ss = sessions.findByDoctorIdAndDayOfWeekOrderByStartTimeAsc(d.getId(), today.getDayOfWeek());
            sb.append(d.displayName()).append(": ");
            if (ss.isEmpty()) sb.append("not in today");
            else sb.append(ss.stream().map(s -> Fmt.time(s.getStartTime()) + " to " + Fmt.time(s.getEndTime()))
                    .collect(Collectors.joining(", ")));
            sb.append("\n");
        }
        reply(t, sb.toString().trim(), List.of(MessageTexts.BTN_BOOK));
    }

    private void replyAddress(Turn t) {
        Clinic c = t.clinic();
        if (c.getAddress() == null || c.getAddress().isBlank()) {
            handoff(t, "Asked for the clinic address");
            return;
        }
        String where = c.getAddress().trim();
        if (c.getLandmark() != null && !c.getLandmark().isBlank()) where += " (" + c.getLandmark().trim() + ")";
        if (c.getCity() != null && !c.getCity().isBlank() && !where.toLowerCase().contains(c.getCity().toLowerCase())) where += ", " + c.getCity();
        reply(t, c.getName() + " is at " + where + ". Would you like to book a visit?", List.of(MessageTexts.BTN_BOOK));
    }

    // =====================================================================
    // Follow-ups (called by the scheduler)
    // =====================================================================

    /** Messages the patient on the follow-up due date with two open slots. */
    @Transactional
    public boolean offerFollowUp(FollowUp f) {
        Clinic clinic = clinics.findById(f.getClinicId()).orElse(null);
        Patient patient = patients.findById(f.getPatientId()).orElse(null);
        if (clinic == null || patient == null || patient.isOptedOut() || !clinic.isFollowupsEnabled()) return false;

        Long doctorId = f.getDoctorId() != null ? f.getDoctorId() : patient.getPreferredDoctorId();
        List<Doctor> candidates = bookableDoctors(clinic.getId());
        Doctor d = candidates.stream().filter(x -> x.getId().equals(doctorId)).findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
        if (d == null) return false;

        List<LocalDateTime> picks = new ArrayList<>();
        for (int i = 0; i < 7 && picks.size() < 2; i++) {
            List<LocalDateTime> free = slots.freeSlots(d, LocalDate.now().plusDays(i), true);
            for (LocalDateTime s : free) {
                if (picks.size() >= 2) break;
                if (picks.isEmpty() || !picks.get(0).toLocalDate().equals(s.toLocalDate()) || picks.size() == 1) {
                    if (!picks.contains(s)) picks.add(s);
                }
            }
        }
        if (picks.isEmpty()) return false;

        Conversation conv = outbound.conversationFor(clinic.getId(), patient.getId());
        Map<String, String> opts = new LinkedHashMap<>();
        picks.forEach(s -> opts.put(Fmt.relativeDay(s.toLocalDate()) + " " + Fmt.time(s), s.toString()));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("purpose", "BOOK");
        ctx.put("source", "followup");
        ctx.put("doctorId", d.getId());
        ctx.put("followUpId", f.getId());
        ctx.put("reason", f.getTitle());
        ctx.put("options", opts);
        ctx.put("expiresHours", 72);
        setStep(conv, STEP_PICK_SLOT, ctx);

        List<String> labels = new ArrayList<>(opts.keySet());
        List<String> buttons = new ArrayList<>(labels);
        buttons.add(MessageTexts.BTN_ANOTHER_DAY);
        outbound.send(clinic, patient, Outgoing.template(
                MessageTexts.followUpDue(d, f.getTitle(), labels.get(0), labels.size() > 1 ? labels.get(1) : null),
                buttons, com.siletry.messaging.gateway.WhatsAppGateway.TemplateMessage.of(
                        TemplateCatalog.FOLLOWUP_DUE.name(), f.getTitle(), d.displayName())));
        f.setStatus(FollowUp.Status.OFFERED);
        f.setNotifiedAt(LocalDateTime.now());
        return true;
    }

    // =====================================================================
    // Handoff
    // =====================================================================

    void handoff(Turn t, String reason) {
        clearStep(t.conv());
        if (t.conv().getState() != ConversationState.NEEDS_YOU) {
            reply(t, MessageTexts.handedOff());
        }
        markNeedsYou(t, reason);
    }

    /** Bot finished this exchange. Leaves threads that are waiting for staff in "Needs you". */
    private void resolved(Turn t) {
        if (t.conv().getState() != ConversationState.NEEDS_YOU) t.conv().setState(ConversationState.HANDLED);
    }

    private void markNeedsYou(Turn t, String reason) {
        t.conv().setState(ConversationState.NEEDS_YOU);
        if (reason != null) t.conv().setHandoffReason(reason);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private void reply(Turn t, String body) {
        reply(t, body, List.of());
    }

    private void reply(Turn t, String body, List<String> buttons) {
        outbound.send(t.clinic(), t.patient(), Outgoing.bot(body, buttons));
    }

    private List<Doctor> bookableDoctors(Long clinicId) {
        return doctors.findByClinicIdAndActiveTrueOrderByNameAsc(clinicId).stream()
                .filter(Doctor::isBookableOnWhatsapp)
                .filter(d -> !sessions.findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(d.getId()).isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<Doctor> doctorsWorkingNow(Long clinicId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        return doctors.findByClinicIdAndActiveTrueOrderByNameAsc(clinicId).stream().filter(d -> {
            List<DoctorSession> ss = sessions.findByDoctorIdAndDayOfWeekOrderByStartTimeAsc(d.getId(), today.getDayOfWeek());
            // Open now, or opening within the next hour
            return ss.stream().anyMatch(s -> now.isBefore(s.getEndTime()) && now.plusHours(1).isAfter(s.getStartTime()));
        }).toList();
    }

    private String lastSessionEnd(Doctor d, LocalDate date) {
        return sessions.findByDoctorIdAndDayOfWeekOrderByStartTimeAsc(d.getId(), date.getDayOfWeek()).stream()
                .map(DoctorSession::getEndTime).max(Comparator.naturalOrder()).map(Fmt::time).orElse(null);
    }

    private Doctor doctorFromCtx(Map<String, Object> ctx) {
        Object id = ctx.get("doctorId");
        return id == null ? null : doctors.findById(((Number) id).longValue()).orElse(null);
    }

    private static String period(String lower) {
        if (EVENING.matcher(lower).find()) return "EVENING";
        if (AFTERNOON.matcher(lower).find()) return "AFTERNOON";
        if (MORNING.matcher(lower).find()) return "MORNING";
        return null;
    }

    private static List<LocalDateTime> filterPeriod(List<LocalDateTime> list, String period) {
        if (period == null) return list;
        return list.stream().filter(s -> {
            int h = s.getHour();
            return switch (period) {
                case "MORNING" -> h < 12;
                case "AFTERNOON" -> h >= 12 && h < 16;
                default -> h >= 16;
            };
        }).toList();
    }

    private static String holderRef(Conversation c) {
        return "conv:" + c.getId();
    }

    private static String summarize(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    /** Matches a reply (button tap or typed) against offered options. Returns the option's value. */
    static String matchOption(String text, Map<String, String> options) {
        if (options == null || options.isEmpty() || text == null) return null;
        String n = norm(text);
        if (n.isEmpty()) return null;
        for (var e : options.entrySet()) if (norm(e.getKey()).equals(n)) return e.getValue();
        if (n.matches("\\d")) {
            int idx = Integer.parseInt(n) - 1;
            if (idx >= 0 && idx < options.size()) return new ArrayList<>(options.values()).get(idx);
        }
        if (n.length() >= 3) {
            List<String> hits = options.entrySet().stream().filter(e -> norm(e.getKey()).startsWith(n)
                    || norm(e.getKey()).endsWith(n)).map(Map.Entry::getValue).toList();
            if (hits.size() == 1) return hits.get(0);
        }
        List<String> contained = options.entrySet().stream().filter(e -> n.contains(norm(e.getKey())))
                .map(Map.Entry::getValue).toList();
        return contained.size() == 1 ? contained.get(0) : null;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static Pattern words(String... ws) {
        String alt = Arrays.stream(ws).map(Pattern::quote).collect(Collectors.joining("|"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])(" + alt + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    }

    // ----- step state -----

    private void expireStaleStep(Conversation c) {
        if (c.getBotStep() == null || c.getBotStepAt() == null) return;
        Object hours = ctx(c).get("expiresHours");
        int h = hours instanceof Number num ? num.intValue() : DEFAULT_STEP_HOURS;
        if (c.getBotStepAt().isBefore(LocalDateTime.now().minusHours(h))) clearStep(c);
    }

    private void setStep(Conversation c, String step, Map<String, Object> ctx) {
        c.setBotStep(step);
        c.setBotStepAt(LocalDateTime.now());
        try {
            c.setBotContext(mapper.writeValueAsString(ctx));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void clearStep(Conversation c) {
        if (c.getBotStep() != null) slots.release(holderRef(c));
        c.setBotStep(null);
        c.setBotContext(null);
        c.setBotStepAt(null);
    }

    private Map<String, Object> ctx(Conversation c) {
        if (c.getBotContext() == null) return new LinkedHashMap<>();
        try {
            return mapper.readValue(c.getBotContext(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> options(Map<String, Object> ctx) {
        Object o = ctx.get("options");
        if (!(o instanceof Map<?, ?> m)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<Object, Object>) m).forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }
}
