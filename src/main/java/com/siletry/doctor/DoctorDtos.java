package com.siletry.doctor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class DoctorDtos {

    private DoctorDtos() {}

    public enum TodayStatus { IN_CLINIC, ON_LEAVE, OFF_TODAY }

    public record SessionDto(@NotNull DayOfWeek day, @NotNull LocalTime start, @NotNull LocalTime end) {}

    public record LeaveDto(Long id, @NotNull LocalDateTime startAt, @NotNull LocalDateTime endAt, String reason) {}

    public record BookingRules(int minNoticeMinutes, int maxAdvanceDays, Integer maxBotBookingsPerDay,
                               boolean bookableOnWhatsapp) {}

    public record DoctorView(
            Long id, String name, String shortName, String initials, String speciality, String registrationNo,
            int fee, String alertMobile, int slotMinutes, List<String> languages, boolean active,
            TodayStatus todayStatus, int slotsThisWeek, int upcomingLeaveCount,
            BookingRules bookingRules, List<SessionDto> sessions) {}

    public record CreateDoctorRequest(
            @NotBlank String name,
            String speciality,
            String registrationNo,
            @Min(0) int fee,
            String alertMobile,
            @Min(5) @Max(120) Integer slotMinutes,
            List<String> languages) {}

    public record UpdateDoctorRequest(
            String name, String speciality, String registrationNo, Integer fee, String alertMobile,
            Integer slotMinutes, List<String> languages, Boolean active, @Valid BookingRules bookingRules) {}

    public record HoursUpdate(@NotNull @Valid List<SessionDto> sessions) {}

    /** Appointments that fall outside the new hours. Siletry lists them; it never cancels on its own. */
    public record AffectedAppointment(Long appointmentId, LocalDateTime startAt, String patientName, String patientPhone) {}

    public record HoursUpdateResult(DoctorView doctor, List<AffectedAppointment> needToMove) {}
}
