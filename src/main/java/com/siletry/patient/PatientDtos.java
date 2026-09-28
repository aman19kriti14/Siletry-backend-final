package com.siletry.patient;

import com.siletry.appointment.AppointmentStatus;
import com.siletry.appointment.BookedBy;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class PatientDtos {

    private PatientDtos() {}

    public record CreatePatientRequest(
            @NotBlank String name, @NotBlank String phone, Integer age, String gender,
            String preferredLanguage, Long preferredDoctorId,
            String emergencyContactName, String emergencyContactPhone, Boolean whatsappOptIn, String notes) {}

    public record UpdatePatientRequest(
            String name, String phone, Integer age, String gender, String preferredLanguage, Long preferredDoctorId,
            String emergencyContactName, String emergencyContactPhone, Boolean whatsappOptIn, String notes) {}

    public record PatientRow(Long id, String name, String initials, String phone, Long patientNo, Integer age,
                             String gender, String language, LocalDateTime lastVisitAt,
                             LocalDateTime nextAppointmentAt, int visits, String dueFollowUp) {}

    public record PageResult<T>(List<T> items, int page, int size, long total) {}

    public record VisitRow(Long appointmentId, LocalDateTime startAt, String reason, Long doctorId, String doctorName,
                           BookedBy bookedBy, AppointmentStatus status) {}

    public record NextAppointment(Long appointmentId, LocalDateTime startAt, Long doctorId, String doctorName,
                                  String reason, AppointmentStatus status, BookedBy bookedBy,
                                  LocalDateTime bookedAt) {}

    public record FollowUpRow(Long id, String title, LocalDate dueDate, String status, String dueLabel) {}

    public record Stats(int visits, int noShows, int bookedBySiletry, LocalDate patientSince) {}

    public record Details(boolean whatsappOptIn, LocalDateTime optInAt, boolean optedOut, String preferredLanguage,
                          Long preferredDoctorId, String preferredDoctorName, String usualTime,
                          String emergencyContactName, String emergencyContactPhone, String notes) {}

    public record PatientProfile(
            Long id, String name, String initials, Integer age, String gender, String phone, Long patientNo,
            String language, String dueFollowUp, Long conversationId,
            NextAppointment nextAppointment, Stats stats, Details details,
            List<FollowUpRow> followUps, List<VisitRow> visits) {}
}
