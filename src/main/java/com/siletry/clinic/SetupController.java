package com.siletry.clinic;

import com.siletry.auth.AppUserRepository;
import com.siletry.auth.CurrentUser;
import com.siletry.auth.OtpService;
import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.doctor.DoctorSession;
import com.siletry.doctor.DoctorSessionRepository;
import com.siletry.messaging.OutboundMessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The 5-step clinic setup wizard: clinic → doctors & hours → WhatsApp number → automations → go live.
 * Each step saves as you go ("Progress saves automatically").
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    public record Block(@NotNull LocalTime start, @NotNull LocalTime end) {}

    public record DoctorRow(Long id, @NotBlank String name, String speciality, Integer fee, Integer slotMinutes) {}

    public record SetupState(
            int step, boolean live,
            String name, String clinicType, String city, String address, String landmark, List<String> languages,
            Integer doctorCountHint,
            List<DoctorRow> doctors, List<DayOfWeek> days, Block morning, Block evening,
            String whatsappNumber, boolean whatsappConnected, String whatsappNumberChoice, boolean testMode,
            boolean apiConnected, String verifiedName, String phoneNumberId,
            boolean reminders, boolean followups, boolean delayAnnouncements, boolean googleReviews, boolean queueUpdates,
            String ownerPhone) {}

    public record ClinicStep(@NotBlank String name, String clinicType, String city, String address, String landmark,
                             List<String> languages) {}

    public record DoctorsStep(@NotNull @Valid List<DoctorRow> doctors, @NotNull List<DayOfWeek> days,
                              @Valid Block morning, @Valid Block evening) {}

    public record WhatsAppStep(String choice, String number) {}

    public record AutomationsStep(Boolean reminders, Boolean followups, Boolean delayAnnouncements,
                                  Boolean googleReviews, Boolean queueUpdates) {}

    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final DoctorSessionRepository sessions;
    private final AppUserRepository users;
    private final OutboundMessageService outbound;
    private final WhatsAppConnectService connectService;
    private final boolean devTools;

    public SetupController(ClinicRepository clinics, DoctorRepository doctors, DoctorSessionRepository sessions,
                           AppUserRepository users, OutboundMessageService outbound, WhatsAppConnectService connectService,
                           @Value("${siletry.dev-tools}") boolean devTools) {
        this.clinics = clinics;
        this.doctors = doctors;
        this.sessions = sessions;
        this.users = users;
        this.outbound = outbound;
        this.connectService = connectService;
        this.devTools = devTools;
    }

    private Clinic mine() {
        return clinics.findById(CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("Clinic"));
    }

    private void advance(Clinic c, int toStep) {
        if (!c.isLive() && c.currentStep() < toStep) c.setOnboardingStep(toStep);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public SetupState state() {
        return state(mine());
    }

    @PutMapping("/clinic")
    @Transactional
    public SetupState clinic(@Valid @RequestBody ClinicStep req) {
        CurrentUser.requireOwner();
        Clinic c = mine();
        c.setName(req.name().trim());
        c.setClinicType(req.clinicType());
        c.setCity(blankToNull(req.city()));
        c.setAddress(blankToNull(req.address()));
        c.setLandmark(blankToNull(req.landmark()));
        if (req.address() != null && c.getArea() == null) c.setArea(guessArea(req.address()));
        List<String> langs = req.languages() == null || req.languages().isEmpty() ? List.of("en") : req.languages();
        c.setReplyLanguages(String.join(",", langs));
        advance(c, 2);
        return state(c);
    }

    @PutMapping("/doctors")
    @Transactional
    public SetupState doctors(@Valid @RequestBody DoctorsStep req) {
        CurrentUser.requireOwner();
        Clinic c = mine();
        if (req.doctors().isEmpty()) throw ApiException.badRequest("Add at least one doctor");
        if (req.days().isEmpty()) throw ApiException.badRequest("Pick at least one working day");
        if (req.morning() == null && req.evening() == null) throw ApiException.badRequest("Add morning or evening hours");
        for (Block b : Arrays.asList(req.morning(), req.evening())) {
            if (b != null && !b.end().isAfter(b.start())) throw ApiException.badRequest("Hours must end after they start");
        }
        if (req.morning() != null && req.evening() != null && req.evening().start().isBefore(req.morning().end())) {
            throw ApiException.badRequest("Evening hours overlap the morning");
        }

        Set<Long> kept = new HashSet<>();
        for (DoctorRow row : req.doctors()) {
            Doctor d = row.id() == null ? new Doctor()
                    : doctors.findByIdAndClinicId(row.id(), c.getId()).orElseGet(Doctor::new);
            d.setClinicId(c.getId());
            d.setName(row.name().trim());
            d.setSpeciality(blankToNull(row.speciality()));
            d.setFee(row.fee() == null ? 0 : Math.max(0, row.fee()));
            d.setSlotMinutes(row.slotMinutes() == null ? 15 : Math.max(5, Math.min(120, row.slotMinutes())));
            if (d.getLanguages() == null || d.getLanguages().equals("en")) d.setLanguages(c.getReplyLanguages() == null ? "en" : c.getReplyLanguages());
            d.setActive(true);
            d = doctors.save(d);
            kept.add(d.getId());

            sessions.deleteByDoctorId(d.getId());
            sessions.flush();
            for (DayOfWeek day : req.days()) {
                if (req.morning() != null) session(c, d, day, req.morning());
                if (req.evening() != null) session(c, d, day, req.evening());
            }
        }
        // Doctors removed in the wizard are switched off, not deleted (they may have bookings)
        doctors.findByClinicIdOrderByNameAsc(c.getId()).stream()
                .filter(d -> !kept.contains(d.getId()))
                .forEach(d -> d.setActive(false));
        advance(c, 3);
        return state(c);
    }

    @PutMapping("/whatsapp")
    @Transactional
    public SetupState whatsapp(@RequestBody WhatsAppStep req) {
        CurrentUser.requireOwner();
        Clinic c = mine();
        c.setWhatsappNumberChoice("NEW".equalsIgnoreCase(req.choice()) ? "NEW" : "CURRENT");
        if (!"NEW".equalsIgnoreCase(req.choice()) && req.number() != null && !req.number().isBlank()) {
            String n = Phone.normalize(req.number());
            if (n == null || n.length() < 11) throw ApiException.badRequest("Enter a valid WhatsApp number");
            clinics.findByWhatsappNumber(n).filter(o -> !o.getId().equals(c.getId()))
                    .ifPresent(o -> { throw ApiException.conflict("This number is linked to another clinic"); });
            if (!n.equals(c.getWhatsappNumber())) c.setWhatsappConnected(false);
            c.setWhatsappNumber(n);
        }
        advance(c, 4);
        return state(c);
    }

    /** Test mode only: marks the number connected without Meta, so the rest of the app can be tried. */
    @PostMapping("/whatsapp/connect")
    @Transactional
    public SetupState connect() {
        CurrentUser.requireOwner();
        Clinic c = mine();
        if (!devTools) throw ApiException.badRequest("Paste the WhatsApp API details to connect");
        if (c.getWhatsappNumber() == null) throw ApiException.badRequest("Enter the clinic's WhatsApp number first");
        c.setWhatsappConnected(true);
        return state(c);
    }

    /** Real connection: details from the clinic's Meta account (set up together with the clinic). */
    @PostMapping("/whatsapp/credentials")
    public Map<String, Object> credentials(@Valid @RequestBody WhatsAppConnectService.Credentials req) {
        CurrentUser.requireOwner();
        var result = connectService.connect(CurrentUser.clinicId(), req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        out.put("state", state(mine()));
        return out;
    }

    @PostMapping("/whatsapp/disconnect")
    @Transactional
    public SetupState disconnect() {
        CurrentUser.requireOwner();
        Clinic c = mine();
        connectService.disconnect(c);
        return state(c);
    }

    @PutMapping("/automations")
    @Transactional
    public SetupState automations(@RequestBody AutomationsStep req) {
        CurrentUser.requireOwner();
        Clinic c = mine();
        if (req.reminders() != null) c.setRemindersEnabled(req.reminders());
        if (req.followups() != null) c.setFollowupsEnabled(req.followups());
        if (req.delayAnnouncements() != null) c.setDelayAnnouncementsEnabled(req.delayAnnouncements());
        if (req.googleReviews() != null) c.setGoogleReviewsEnabled(req.googleReviews());
        if (req.queueUpdates() != null) c.setQueueUpdatesEnabled(req.queueUpdates());
        advance(c, 5);
        return state(c);
    }

    /** Sends a test message to the owner's own WhatsApp. */
    @PostMapping("/test-message")
    @Transactional(readOnly = true)
    public Map<String, Object> testMessage() {
        Clinic c = mine();
        String phone = users.findById(CurrentUser.userId()).map(u -> u.getPhone()).orElse(null);
        if (phone == null) throw ApiException.badRequest("Add your mobile number in Settings first");
        var r = outbound.alertStaff(phone, c.getName(), "This is a test from Siletry. " + c.getName() + " is ready to go live.", null);
        if (!r.ok()) throw ApiException.badRequest("Test not sent: " + r.error());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sentTo", OtpService.mask(phone));
        out.put("testMode", !routerIsReal());
        return out;
    }

    @PostMapping("/go-live")
    @Transactional
    public SetupState goLive() {
        CurrentUser.requireOwner();
        Clinic c = mine();
        if (doctors.findByClinicIdAndActiveTrueOrderByNameAsc(c.getId()).isEmpty()) {
            throw ApiException.badRequest("Add at least one doctor with hours before going live");
        }
        c.setLiveAt(LocalDateTime.now());
        c.setOnboardingStep(6);
        c.setBotEnabled(true);
        return state(c);
    }

    // ------------------------------------------------------------------

    @org.springframework.beans.factory.annotation.Autowired
    private com.siletry.messaging.gateway.GatewayRouter router;

    private boolean routerIsReal() {
        return router != null && router.adminIsReal();
    }

    private SetupState state(Clinic c) {
        List<Doctor> active = doctors.findByClinicIdAndActiveTrueOrderByNameAsc(c.getId());
        List<DoctorRow> rows = active.stream()
                .sorted(Comparator.comparing(Doctor::getId))
                .map(d -> new DoctorRow(d.getId(), d.getName(), d.getSpeciality(), d.getFee(), d.getSlotMinutes()))
                .toList();

        // Hours shown in the wizard come from the first doctor
        List<DayOfWeek> days = new ArrayList<>();
        Block morning = null, evening = null;
        if (!active.isEmpty()) {
            List<DoctorSession> ss = sessions.findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(rows.get(0).id());
            days = ss.stream().map(DoctorSession::getDayOfWeek).distinct().sorted().collect(Collectors.toList());
            for (DoctorSession s : ss) {
                if (s.getStartTime().isBefore(LocalTime.NOON)) {
                    if (morning == null) morning = new Block(s.getStartTime(), s.getEndTime());
                } else if (evening == null) {
                    evening = new Block(s.getStartTime(), s.getEndTime());
                }
            }
        }
        String ownerPhone = users.findById(CurrentUser.userId()).map(u -> Phone.pretty(u.getPhone())).orElse(null);

        return new SetupState(c.currentStep(), c.isLive(),
                c.getName(), c.getClinicType(), c.getCity(), c.getAddress(), c.getLandmark(), c.replyLanguageList(),
                c.getDoctorCountHint(), rows, days, morning, evening,
                Phone.pretty(c.getWhatsappNumber()), c.isWhatsappConnected(), c.getWhatsappNumberChoice(), devTools,
                c.hasWhatsAppApi(), c.getWaVerifiedName(), c.getWaPhoneNumberId(),
                c.isRemindersEnabled(), c.isFollowupsEnabled(), c.delayAnnouncementsOn(), c.googleReviewsOn(),
                c.isQueueUpdatesEnabled(), ownerPhone);
    }

    private void session(Clinic c, Doctor d, DayOfWeek day, Block b) {
        DoctorSession s = new DoctorSession();
        s.setClinicId(c.getId());
        s.setDoctorId(d.getId());
        s.setDayOfWeek(day);
        s.setStartTime(b.start());
        s.setEndTime(b.end());
        sessions.save(s);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** "42, 11th Main, Jayanagar 4th Block, 560011" → "Jayanagar 4th Block" (best effort, editable later). */
    private static String guessArea(String address) {
        String[] parts = address.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (!p.isEmpty() && !p.matches(".*\\d{6}.*") && !p.matches("\\d+.*")) return p;
        }
        return null;
    }
}
