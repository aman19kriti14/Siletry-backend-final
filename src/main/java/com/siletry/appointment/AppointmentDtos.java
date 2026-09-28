package com.siletry.appointment;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public final class AppointmentDtos {

    private AppointmentDtos() {}

    public record AppointmentRow(
            Long id, LocalDateTime startAt, LocalDateTime endAt, String time,
            Long patientId, String patientName, String patientPhone,
            String reason, Long doctorId, String doctorName,
            BookedBy bookedBy, AppointmentStatus status, boolean emergency,
            Integer tokenNumber, String frontDeskNote, String source, Long conversationId,
            String reminderResponse) {}

    public record TabCounts(long today, long thisWeek, long noShows, long cancelled) {}

    public record AppointmentList(String dateLabel, TabCounts counts, List<AppointmentRow> items) {}

    public record NewPatient(String name, String phone) {}

    /** Book a visit (staff). Either patientId or newPatient. */
    public record CreateAppointmentRequest(
            Long patientId, NewPatient newPatient,
            @NotNull Long doctorId, @NotNull LocalDateTime startAt, Integer durationMinutes,
            String reason, String frontDeskNote,
            Boolean sendConfirmation, Boolean remindEveningBefore, Long followUpId) {}

    public record RescheduleRequest(@NotNull LocalDateTime startAt, Long doctorId, Boolean notifyPatient) {}

    public record CancelRequest(String reason, Boolean notifyPatient) {}

    public record UpdateAppointmentRequest(String reason, String frontDeskNote, Boolean emergency, Boolean remindEveningBefore) {}

    /** Preview of the WhatsApp confirmation shown in the Book a visit modal. */
    public record ConfirmationPreview(String message, String language) {}
}
