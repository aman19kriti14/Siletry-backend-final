package com.siletry.messaging;

import com.siletry.appointment.AppointmentRepository;
import com.siletry.appointment.AppointmentStatus;
import com.siletry.auth.AppUser;
import com.siletry.auth.AppUserRepository;
import com.siletry.common.Phone;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.queue.QueueDtos.QueueView;
import com.siletry.queue.QueueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Doctors and receptionists talk to the central Siletry number.
 * "next" → Call next, "queue" → who's waiting, "today" → today's count.
 */
@Service
public class AdminCommandService {

    private final AppUserRepository users;
    private final DoctorRepository doctors;
    private final QueueService queue;
    private final AppointmentRepository appointments;
    private final OutboundMessageService outbound;
    private final com.siletry.clinic.ClinicRepository clinics;

    public AdminCommandService(AppUserRepository users, DoctorRepository doctors, QueueService queue,
                               AppointmentRepository appointments, OutboundMessageService outbound,
                               com.siletry.clinic.ClinicRepository clinics) {
        this.clinics = clinics;
        this.users = users;
        this.doctors = doctors;
        this.queue = queue;
        this.appointments = appointments;
        this.outbound = outbound;
    }

    private record Sender(Long clinicId, Doctor doctor) {}

    @Transactional
    public String handle(String rawPhone, String text) {
        String phone = Phone.normalize(rawPhone);
        Optional<Sender> sender = identify(phone);
        if (sender.isEmpty()) {
            return send(phone, "This number isn't linked to a Siletry clinic. Add it as a doctor's alert mobile or a staff phone in Siletry.", null);
        }
        Long clinicId = sender.get().clinicId();
        String lower = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Doctor> active = doctors.findByClinicIdAndActiveTrueOrderByNameAsc(clinicId);

        if (lower.startsWith("next") || lower.equals(MessageTexts.BTN_CALL_NEXT.toLowerCase(Locale.ROOT))) {
            Doctor d = sender.get().doctor();
            String rest = lower.replaceFirst("^(call\\s+)?next", "").trim();
            if (!rest.isEmpty()) {
                d = active.stream().filter(x -> x.getName().toLowerCase(Locale.ROOT).contains(rest)
                        || x.shortName().toLowerCase(Locale.ROOT).contains(rest)).findFirst().orElse(d);
            }
            if (d == null && active.size() == 1) d = active.get(0);
            if (d == null) {
                List<String> buttons = active.stream().limit(3).map(x -> "Next " + x.shortName()).toList();
                return send(phone, "Which doctor's queue?", buttons);
            }
            QueueView q = queue.callNext(clinicId, d.getId());
            if (q.current() == null) return send(phone, "Nobody is waiting for " + d.displayName() + " right now.", null);
            String after = q.waiting().isEmpty() ? "No one else is waiting."
                    : q.waiting().size() + " waiting. Next up: token #" + q.waiting().get(0).tokenNumber() + ".";
            return send(phone, "Called token #" + q.current().tokenNumber() + " (" + q.current().patientName() + "). " + after,
                    List.of(MessageTexts.BTN_CALL_NEXT));
        }

        java.util.regex.Matcher late = java.util.regex.Pattern
                .compile("(?:running\\s+)?(?:late\\s+(?:by\\s+)?(\\d{1,3})|(\\d{1,3})\\s*(?:m|min|mins|minutes)?\\s+late)")
                .matcher(lower);
        if (late.find()) {
            int minutes = Integer.parseInt(late.group(1) != null ? late.group(1) : late.group(2));
            com.siletry.clinic.Clinic clinic = clinics.findById(clinicId).orElseThrow();
            if (!clinic.delayAnnouncementsOn()) {
                return send(phone, "Delay announcements are switched off. Turn them on in Siletry → Automations.", null);
            }
            Doctor d = sender.get().doctor();
            if (d == null && active.size() == 1) d = active.get(0);
            if (d == null) return send(phone, "Which doctor is late? Send it from the doctor's own phone, or use Announce delay in Siletry.", null);
            int n = queue.announceDelay(clinicId, d.getId(), Math.max(5, Math.min(minutes, 240)), null).patientsNotified();
            return send(phone, "Done. Told " + n + " patient" + (n == 1 ? "" : "s") + " that " + d.displayName() + " is running about " + minutes + " minutes late.", null);
        }

        if (lower.startsWith("queue") || lower.equals("status")) {
            StringBuilder sb = new StringBuilder();
            for (Doctor d : active) {
                QueueView q = queue.view(clinicId, d.getId());
                sb.append(d.displayName()).append(": ")
                        .append(q.current() == null ? "nobody in" : "with #" + q.current().tokenNumber())
                        .append(", ").append(q.waiting().size()).append(" waiting\n");
            }
            return send(phone, sb.length() == 0 ? "No doctors set up yet." : sb.toString().trim(), List.of(MessageTexts.BTN_CALL_NEXT));
        }

        if (lower.startsWith("today")) {
            LocalDateTime ds = LocalDate.now().atStartOfDay();
            long count = appointments.findInRange(clinicId, ds, ds.plusDays(1)).stream()
                    .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).count();
            return send(phone, count + " appointments today.", null);
        }

        return send(phone, "Commands: NEXT (call the next patient), QUEUE (who's waiting), TODAY (today's count), LATE 30 (tell today's patients you're 30 minutes late).",
                List.of(MessageTexts.BTN_CALL_NEXT));
    }

    private Optional<Sender> identify(String phone) {
        if (phone == null) return Optional.empty();
        List<Doctor> ds = doctors.findByAlertMobileAndActiveTrue(phone);
        if (!ds.isEmpty()) return Optional.of(new Sender(ds.get(0).getClinicId(), ds.get(0)));
        List<AppUser> us = users.findByPhoneAndActiveTrue(phone);
        if (!us.isEmpty()) {
            AppUser u = us.get(0);
            Doctor d = u.getDoctorId() == null ? null : doctors.findById(u.getDoctorId()).orElse(null);
            return Optional.of(new Sender(u.getClinicId(), d));
        }
        return Optional.empty();
    }

    private String send(String phone, String body, List<String> buttons) {
        outbound.sendAdmin(phone, body, buttons);
        return body;
    }
}
