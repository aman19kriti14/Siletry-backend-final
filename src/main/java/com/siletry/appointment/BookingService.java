package com.siletry.appointment;

import com.siletry.common.ApiException;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.followup.FollowUp;
import com.siletry.followup.FollowUpRepository;
import com.siletry.patient.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Creates, moves and cancels appointments. Used by both the web app and the WhatsApp bot. */
@Service
public class BookingService {

    private final AppointmentRepository appointments;
    private final DoctorRepository doctors;
    private final PatientRepository patients;
    private final FollowUpRepository followUps;
    private final SlotService slots;
    private final SlotHoldRepository holds;

    public BookingService(AppointmentRepository appointments, DoctorRepository doctors, PatientRepository patients,
                          FollowUpRepository followUps, SlotService slots, SlotHoldRepository holds) {
        this.appointments = appointments;
        this.doctors = doctors;
        this.patients = patients;
        this.followUps = followUps;
        this.slots = slots;
        this.holds = holds;
    }

    public record BookCommand(Long clinicId, Long patientId, Long doctorId, LocalDateTime startAt,
                              Integer durationMinutes, String reason, String frontDeskNote, BookedBy bookedBy,
                              String source, boolean remindEveningBefore, String holderRef, Long userId,
                              Long followUpId) {}

    @Transactional(noRollbackFor = ApiException.class)
    public Appointment book(BookCommand cmd, boolean forBot) {
        Doctor d = doctors.findByIdAndClinicId(cmd.doctorId(), cmd.clinicId()).orElseThrow(() -> ApiException.notFound("Doctor"));
        patients.findByIdAndClinicId(cmd.patientId(), cmd.clinicId()).orElseThrow(() -> ApiException.notFound("Patient"));
        LocalDateTime start = cmd.startAt().withSecond(0).withNano(0);
        int duration = cmd.durationMinutes() == null || cmd.durationMinutes() <= 0 ? d.getSlotMinutes() : cmd.durationMinutes();

        slots.validateBookable(d, start, duration, cmd.holderRef(), null, forBot);

        Appointment a = new Appointment();
        a.setClinicId(cmd.clinicId());
        a.setPatientId(cmd.patientId());
        a.setDoctorId(d.getId());
        a.setStartAt(start);
        a.setEndAt(start.plusMinutes(duration));
        a.setDurationMinutes(duration);
        a.setReason(clean(cmd.reason()));
        a.setFrontDeskNote(clean(cmd.frontDeskNote()));
        a.setBookedBy(cmd.bookedBy());
        a.setSource(cmd.source());
        a.setStatus(AppointmentStatus.CONFIRMED);
        a.setFeeAtBooking(d.getFee());
        a.setRemindEveningBefore(cmd.remindEveningBefore());
        a.setCreatedByUserId(cmd.userId());
        a.setSlotKey(Appointment.slotKeyFor(d.getId(), start));
        a = appointments.saveAndFlush(a); // unique slot_key: a double booking fails right here

        if (cmd.holderRef() != null) holds.deleteByHolder(cmd.holderRef());

        if (cmd.followUpId() != null) {
            Long apptId = a.getId();
            followUps.findByIdAndClinicId(cmd.followUpId(), cmd.clinicId()).ifPresent(f -> {
                f.setStatus(FollowUp.Status.BOOKED);
                f.setAppointmentId(apptId);
            });
        }
        return a;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Appointment reschedule(Long clinicId, Long appointmentId, LocalDateTime newStart, Long newDoctorId,
                                  String holderRef, boolean forBot) {
        Appointment a = appointments.findByIdAndClinicId(appointmentId, clinicId).orElseThrow(() -> ApiException.notFound("Appointment"));
        if (a.getStatus() != AppointmentStatus.CONFIRMED) {
            throw ApiException.badRequest("Only confirmed appointments can be rescheduled");
        }
        Long doctorId = newDoctorId == null ? a.getDoctorId() : newDoctorId;
        Doctor d = doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> ApiException.notFound("Doctor"));
        LocalDateTime start = newStart.withSecond(0).withNano(0);
        int duration = doctorId.equals(a.getDoctorId()) ? a.getDurationMinutes() : d.getSlotMinutes();

        slots.validateBookable(d, start, duration, holderRef, a.getId(), forBot);

        // Free the old slot first so the unique key doesn't clash with itself
        a.setSlotKey(null);
        appointments.saveAndFlush(a);

        a.setDoctorId(d.getId());
        a.setStartAt(start);
        a.setEndAt(start.plusMinutes(duration));
        a.setDurationMinutes(duration);
        a.setFeeAtBooking(d.getFee());
        a.setReminderSentAt(null);
        a.setReminderResponse(null);
        a.setSlotKey(Appointment.slotKeyFor(d.getId(), start));
        a = appointments.saveAndFlush(a);
        if (holderRef != null) holds.deleteByHolder(holderRef);
        return a;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Appointment cancel(Long clinicId, Long appointmentId, String reason) {
        Appointment a = appointments.findByIdAndClinicId(appointmentId, clinicId).orElseThrow(() -> ApiException.notFound("Appointment"));
        if (a.getStatus() == AppointmentStatus.CANCELLED) return a;
        if (a.getStatus() == AppointmentStatus.SEEN) throw ApiException.badRequest("This visit is already done");
        a.setStatus(AppointmentStatus.CANCELLED);
        a.setCancelledAt(LocalDateTime.now());
        a.setCancelReason(clean(reason));
        a.setSlotKey(null);
        return a;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Appointment markNoShow(Long clinicId, Long appointmentId) {
        Appointment a = appointments.findByIdAndClinicId(appointmentId, clinicId).orElseThrow(() -> ApiException.notFound("Appointment"));
        if (a.getStatus() != AppointmentStatus.CONFIRMED && a.getStatus() != AppointmentStatus.WAITING) {
            throw ApiException.badRequest("Only confirmed or waiting appointments can be marked as no-show");
        }
        a.setStatus(AppointmentStatus.NO_SHOW);
        a.setSlotKey(null);
        return a;
    }

    private String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > 500 ? t.substring(0, 500) : t);
    }
}
