package com.siletry.queue;

import com.siletry.appointment.AppointmentStatus;
import com.siletry.appointment.BookedBy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public final class QueueDtos {

    private QueueDtos() {}

    public record QueueEntry(Long appointmentId, Integer tokenNumber, Long patientId, String patientName, String phone,
                             String reason, AppointmentStatus status, boolean emergency, BookedBy bookedBy,
                             LocalDateTime scheduledAt, LocalDateTime checkedInAt, int position, int etaMinutes,
                             long waitedMinutes) {}

    public record QueueView(Long doctorId, String doctorName, QueueEntry current, List<QueueEntry> waiting,
                            int avgConsultMinutes, boolean stale, LocalDateTime lastCalledAt, long seenToday) {}

    public record WalkInRequest(Long patientId, String name, String phone, @NotNull Long doctorId,
                                String reason, Boolean emergency) {}

    public record DelayRequest(@Min(5) @Max(240) int minutes, String note) {}

    public record DelayResult(int patientsNotified) {}
}
