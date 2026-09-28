package com.siletry.appointment;

import com.siletry.appointment.AppointmentDtos.*;
import com.siletry.appointment.BookingService.BookCommand;
import com.siletry.auth.CurrentUser;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.ApiException;
import com.siletry.common.Fmt;
import com.siletry.common.Phone;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.messaging.Conversation;
import com.siletry.messaging.ConversationRepository;
import com.siletry.messaging.MessageTexts;
import com.siletry.messaging.NotificationService;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientRepository;
import com.siletry.patient.PatientService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The Appointments screen: tabs, filters, and staff actions. */
@Service
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final BookingService booking;
    private final PatientService patientService;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final ClinicRepository clinics;
    private final ConversationRepository conversations;
    private final NotificationService notify;

    public AppointmentService(AppointmentRepository appointments, BookingService booking, PatientService patientService,
                              PatientRepository patients, DoctorRepository doctors, ClinicRepository clinics,
                              ConversationRepository conversations, NotificationService notify) {
        this.appointments = appointments;
        this.booking = booking;
        this.patientService = patientService;
        this.patients = patients;
        this.doctors = doctors;
        this.clinics = clinics;
        this.conversations = conversations;
        this.notify = notify;
    }

    @Transactional(readOnly = true)
    public AppointmentList list(String tab, LocalDate date, Long doctorId, AppointmentStatus status, String bookedBy) {
        Long clinicId = CurrentUser.clinicId();
        LocalDate day = date == null ? LocalDate.now() : date;
        LocalDate weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDateTime wFrom = weekStart.atStartOfDay();
        LocalDateTime wTo = wFrom.plusDays(7);

        List<Appointment> week = appointments.findInRange(clinicId, wFrom, wTo);
        List<Appointment> todays = week.stream().filter(a -> a.getStartAt().toLocalDate().equals(day)).toList();
        // Today's list may fall outside the week window if date is far away: fetch directly then
        if (day.isBefore(weekStart) || !day.isBefore(weekStart.plusDays(7))) {
            todays = appointments.findInRange(clinicId, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        }

        TabCounts counts = new TabCounts(
                todays.stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).count(),
                week.stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).count(),
                week.stream().filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).count(),
                week.stream().filter(a -> a.getStatus() == AppointmentStatus.CANCELLED).count());

        String t = tab == null ? "today" : tab.toLowerCase();
        List<Appointment> base = switch (t) {
            case "week" -> week.stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).toList();
            case "noshows" -> week.stream().filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).toList();
            case "cancelled" -> week.stream().filter(a -> a.getStatus() == AppointmentStatus.CANCELLED).toList();
            default -> todays.stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).toList();
        };

        List<Appointment> filtered = base.stream()
                .filter(a -> doctorId == null || a.getDoctorId().equals(doctorId))
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> bookedBy == null || bookedBy.isBlank() || matchesBookedBy(a, bookedBy))
                .toList();

        Clinic clinic = clinics.findById(clinicId).orElseThrow();
        String label = day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)) + " · " + clinic.displayName();
        return new AppointmentList(label, counts, rows(clinicId, filtered));
    }

    private boolean matchesBookedBy(Appointment a, String bookedBy) {
        String b = bookedBy.toUpperCase();
        if (b.equals("STAFF")) return a.getBookedBy() != BookedBy.SILETRY;
        return a.getBookedBy().name().equals(b);
    }

    @Transactional
    public AppointmentRow create(CreateAppointmentRequest req) {
        Long clinicId = CurrentUser.clinicId();
        Long patientId = req.patientId();
        if (patientId == null) {
            if (req.newPatient() == null || req.newPatient().phone() == null) {
                throw ApiException.badRequest("Choose a patient or enter a new patient's name and phone");
            }
            patientId = patientService.findOrCreate(clinicId, req.newPatient().phone(), req.newPatient().name(), true).getId();
        }
        Appointment a = booking.book(new BookCommand(clinicId, patientId, req.doctorId(), req.startAt(),
                req.durationMinutes(), req.reason(), req.frontDeskNote(), BookedBy.FRONT_DESK, "web",
                req.remindEveningBefore() == null || req.remindEveningBefore(), null, CurrentUser.userId(),
                req.followUpId()), false);
        if (req.sendConfirmation() == null || req.sendConfirmation()) notify.bookingConfirmed(a);
        return row(a);
    }

    @Transactional(readOnly = true)
    public ConfirmationPreview preview(Long patientId, Long doctorId, LocalDateTime startAt) {
        Long clinicId = CurrentUser.clinicId();
        Clinic clinic = clinics.findById(clinicId).orElseThrow();
        Doctor d = doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> ApiException.notFound("Doctor"));
        String lang = patientId == null ? "en"
                : patients.findByIdAndClinicId(patientId, clinicId).map(Patient::getPreferredLanguage).orElse("en");
        return new ConfirmationPreview(MessageTexts.booked(clinic, d, startAt), lang == null ? "en" : lang);
    }

    @Transactional
    public AppointmentRow reschedule(Long id, RescheduleRequest req) {
        Appointment a = booking.reschedule(CurrentUser.clinicId(), id, req.startAt(), req.doctorId(), null, false);
        if (req.notifyPatient() == null || req.notifyPatient()) notify.rescheduled(a);
        return row(a);
    }

    @Transactional
    public AppointmentRow cancel(Long id, CancelRequest req) {
        Appointment a = booking.cancel(CurrentUser.clinicId(), id, req == null ? null : req.reason());
        if (req == null || req.notifyPatient() == null || req.notifyPatient()) {
            if (a.getStartAt().isAfter(LocalDateTime.now())) notify.cancelledByClinic(a, req == null ? null : req.reason());
        }
        return row(a);
    }

    @Transactional
    public AppointmentRow noShow(Long id) {
        return row(booking.markNoShow(CurrentUser.clinicId(), id));
    }

    @Transactional
    public AppointmentRow update(Long id, UpdateAppointmentRequest req) {
        Appointment a = appointments.findByIdAndClinicId(id, CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("Appointment"));
        if (req.reason() != null) a.setReason(req.reason().trim());
        if (req.frontDeskNote() != null) a.setFrontDeskNote(req.frontDeskNote().trim());
        if (req.emergency() != null) a.setEmergency(req.emergency());
        if (req.remindEveningBefore() != null) a.setRemindEveningBefore(req.remindEveningBefore());
        return row(a);
    }

    public AppointmentRow row(Appointment a) {
        return rows(a.getClinicId(), List.of(a)).get(0);
    }

    public List<AppointmentRow> rows(Long clinicId, List<Appointment> list) {
        if (list.isEmpty()) return List.of();
        Set<Long> pids = list.stream().map(Appointment::getPatientId).collect(Collectors.toSet());
        Set<Long> dids = list.stream().map(Appointment::getDoctorId).collect(Collectors.toSet());
        Map<Long, Patient> pmap = patients.findAllById(pids).stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        Map<Long, Doctor> dmap = doctors.findByIdIn(dids).stream().collect(Collectors.toMap(Doctor::getId, Function.identity()));
        Map<Long, Long> convByPatient = conversations.findByClinicIdAndPatientIdIn(clinicId, pids).stream()
                .collect(Collectors.toMap(Conversation::getPatientId, Conversation::getId, (x, y) -> x));
        return list.stream().map(a -> {
            Patient p = pmap.get(a.getPatientId());
            Doctor d = dmap.get(a.getDoctorId());
            return new AppointmentRow(a.getId(), a.getStartAt(), a.getEndAt(), Fmt.time(a.getStartAt()),
                    a.getPatientId(), p == null ? null : p.getName(), p == null ? null : Phone.pretty(p.getPhone()),
                    a.getReason(), a.getDoctorId(), d == null ? null : d.shortName(),
                    a.getBookedBy(), a.getStatus(), a.isEmergency(), a.getTokenNumber(), a.getFrontDeskNote(),
                    a.getSource(), convByPatient.get(a.getPatientId()), a.getReminderResponse());
        }).toList();
    }
}
