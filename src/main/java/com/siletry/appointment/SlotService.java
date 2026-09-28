package com.siletry.appointment;

import com.siletry.common.ApiException;
import com.siletry.common.Fmt;
import com.siletry.doctor.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * The slot engine. Slots = weekly sessions, minus leave, minus bookings, minus active holds.
 */
@Service
public class SlotService {

    public enum SlotState { FREE, HELD, BOOKED, BLOCKED }

    public record Slot(LocalDateTime startAt, String label, SlotState state) {}

    public record DaySummary(LocalDate date, String label, int free, boolean closed) {}

    private final DoctorSessionRepository sessions;
    private final DoctorLeaveRepository leaves;
    private final AppointmentRepository appointments;
    private final SlotHoldRepository holds;

    public SlotService(DoctorSessionRepository sessions, DoctorLeaveRepository leaves,
                       AppointmentRepository appointments, SlotHoldRepository holds) {
        this.sessions = sessions;
        this.leaves = leaves;
        this.appointments = appointments;
        this.holds = holds;
    }

    /** All slots of a day with their state. Past slots are left out. */
    @Transactional(readOnly = true)
    public List<Slot> daySlots(Doctor d, LocalDate date, boolean forBot) {
        List<DoctorSession> daySessions = sessions.findByDoctorIdAndDayOfWeekOrderByStartTimeAsc(d.getId(), date.getDayOfWeek());
        if (daySessions.isEmpty()) return List.of();

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime earliest = forBot ? now.plusMinutes(d.getMinNoticeMinutes()) : now.minusMinutes(d.getSlotMinutes() - 1L);
        if (forBot && date.isAfter(LocalDate.now().plusDays(d.getMaxAdvanceDays()))) return List.of();

        List<DoctorLeave> dayLeaves = leaves.findOverlapping(d.getId(), dayStart, dayEnd);
        List<Appointment> booked = appointments.findSlottedOverlapping(d.getId(), dayStart, dayEnd, AppointmentStatus.ACTIVE);
        Set<LocalDateTime> held = new HashSet<>();
        holds.findActive(d.getId(), dayStart, dayEnd, now).forEach(h -> held.add(h.getStartAt()));

        List<Slot> out = new ArrayList<>();
        for (DoctorSession s : daySessions) {
            LocalTime t = s.getStartTime();
            while (!t.plusMinutes(d.getSlotMinutes()).isAfter(s.getEndTime())) {
                LocalDateTime start = date.atTime(t);
                LocalDateTime end = start.plusMinutes(d.getSlotMinutes());
                if (!start.isBefore(earliest)) {
                    SlotState state;
                    if (overlapsLeave(dayLeaves, start, end)) state = SlotState.BLOCKED;
                    else if (overlapsBooking(booked, start, end, null)) state = SlotState.BOOKED;
                    else if (held.contains(start)) state = SlotState.HELD;
                    else state = SlotState.FREE;
                    out.add(new Slot(start, Fmt.time(t), state));
                }
                LocalTime next = t.plusMinutes(d.getSlotMinutes());
                if (next.isBefore(t)) break; // wrapped past midnight
                t = next;
            }
        }
        out.sort(Comparator.comparing(Slot::startAt));
        return out;
    }

    public List<LocalDateTime> freeSlots(Doctor d, LocalDate date, boolean forBot) {
        return daySlots(d, date, forBot).stream()
                .filter(s -> s.state() == SlotState.FREE)
                .map(Slot::startAt)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DaySummary> daySummaries(Doctor d, LocalDate from, int days, boolean forBot) {
        List<DaySummary> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = from.plusDays(i);
            boolean hasSessions = !sessions.findByDoctorIdAndDayOfWeekOrderByStartTimeAsc(d.getId(), date.getDayOfWeek()).isEmpty();
            if (!hasSessions) {
                out.add(new DaySummary(date, Fmt.relativeDay(date), 0, true));
                continue;
            }
            List<Slot> slots = daySlots(d, date, forBot);
            boolean allBlocked = !slots.isEmpty() && slots.stream().allMatch(s -> s.state() == SlotState.BLOCKED);
            int free = (int) slots.stream().filter(s -> s.state() == SlotState.FREE).count();
            out.add(new DaySummary(date, Fmt.relativeDay(date), free, allBlocked));
        }
        return out;
    }

    /** Total offerable slots this week according to weekly hours (the "64 slots this week" figure). */
    @Transactional(readOnly = true)
    public int slotsThisWeek(Doctor d) {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<DoctorLeave> weekLeaves = leaves.findOverlapping(d.getId(), monday.atStartOfDay(), monday.plusDays(7).atStartOfDay());
        int total = 0;
        for (DoctorSession s : sessions.findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(d.getId())) {
            LocalDate date = monday.with(TemporalAdjusters.nextOrSame(s.getDayOfWeek()));
            LocalTime t = s.getStartTime();
            while (!t.plusMinutes(d.getSlotMinutes()).isAfter(s.getEndTime())) {
                LocalDateTime start = date.atTime(t);
                if (!overlapsLeave(weekLeaves, start, start.plusMinutes(d.getSlotMinutes()))) total++;
                LocalTime next = t.plusMinutes(d.getSlotMinutes());
                if (next.isBefore(t)) break;
                t = next;
            }
        }
        return total;
    }

    /** Throws a clear error if this booking isn't allowed. */
    @Transactional(readOnly = true, noRollbackFor = ApiException.class)
    public void validateBookable(Doctor d, LocalDateTime start, int durationMinutes, String holderRef,
                                 Long ignoreAppointmentId, boolean forBot) {
        LocalDateTime end = start.plusMinutes(durationMinutes);
        LocalDateTime now = LocalDateTime.now();

        if (!d.isActive()) throw ApiException.badRequest("This doctor isn't taking appointments");
        if (!end.isAfter(now)) throw ApiException.badRequest("That time has already passed");
        if (!isWithinHours(d, start, end)) {
            throw ApiException.badRequest(d.displayName() + " isn't seeing patients at " + Fmt.when(start));
        }
        if (forBot) {
            if (!d.isBookableOnWhatsapp()) throw ApiException.badRequest("WhatsApp booking is off for this doctor");
            if (start.isBefore(now.plusMinutes(d.getMinNoticeMinutes()))) {
                throw ApiException.badRequest("That slot is too soon to book");
            }
            if (start.toLocalDate().isAfter(LocalDate.now().plusDays(d.getMaxAdvanceDays()))) {
                throw ApiException.badRequest("That's too far ahead to book");
            }
            if (d.getMaxBotBookingsPerDay() != null) {
                LocalDateTime ds = start.toLocalDate().atStartOfDay();
                long count = appointments.countBotBookingsForDay(d.getId(), ds, ds.plusDays(1));
                if (count >= d.getMaxBotBookingsPerDay()) throw ApiException.conflict("WhatsApp bookings are full for that day");
            }
        }
        if (!leaves.findOverlapping(d.getId(), start, end).isEmpty()) {
            throw ApiException.conflict(d.displayName() + " is on leave then");
        }
        List<Appointment> overlapping = appointments.findSlottedOverlapping(d.getId(), start, end, AppointmentStatus.ACTIVE);
        if (overlapsBooking(overlapping, start, end, ignoreAppointmentId)) {
            throw ApiException.conflict("That slot was just booked. Please pick another time.");
        }
        holds.findByHoldKey(Appointment.slotKeyFor(d.getId(), start))
                .filter(h -> h.getExpiresAt().isAfter(now))
                .filter(h -> holderRef == null || !holderRef.equals(h.getHolderRef()))
                .ifPresent(h -> {
                    throw ApiException.conflict("Siletry is offering that slot to another patient on WhatsApp right now");
                });
    }

    public boolean isWithinHours(Doctor d, LocalDateTime start, LocalDateTime end) {
        if (!start.toLocalDate().equals(end.minusNanos(1).toLocalDate())) return false;
        LocalTime st = start.toLocalTime();
        LocalTime en = end.toLocalTime();
        return sessions.findByDoctorIdAndDayOfWeekOrderByStartTimeAsc(d.getId(), start.getDayOfWeek()).stream()
                .anyMatch(s -> !st.isBefore(s.getStartTime()) && !en.isAfter(s.getEndTime()));
    }

    /** Holds these slots for a WhatsApp conversation. Releases that holder's older holds first. */
    @Transactional
    public List<LocalDateTime> hold(Long clinicId, Long doctorId, List<LocalDateTime> starts, String holderRef, int minutes) {
        holds.deleteByHolder(holderRef);
        holds.flush();
        LocalDateTime now = LocalDateTime.now();
        List<LocalDateTime> held = new ArrayList<>();
        for (LocalDateTime start : starts) {
            String key = Appointment.slotKeyFor(doctorId, start);
            Optional<SlotHold> existing = holds.findByHoldKey(key);
            if (existing.isPresent()) {
                SlotHold h = existing.get();
                if (h.getExpiresAt().isAfter(now) && !h.getHolderRef().equals(holderRef)) continue;
                h.setHolderRef(holderRef);
                h.setExpiresAt(now.plusMinutes(minutes));
            } else {
                SlotHold h = new SlotHold();
                h.setClinicId(clinicId);
                h.setDoctorId(doctorId);
                h.setStartAt(start);
                h.setHoldKey(key);
                h.setHolderRef(holderRef);
                h.setExpiresAt(now.plusMinutes(minutes));
                holds.save(h);
            }
            held.add(start);
        }
        return held;
    }

    @Transactional
    public void release(String holderRef) {
        holds.deleteByHolder(holderRef);
    }

    private boolean overlapsLeave(List<DoctorLeave> list, LocalDateTime start, LocalDateTime end) {
        for (DoctorLeave l : list) {
            if (l.getStartAt().isBefore(end) && l.getEndAt().isAfter(start)) return true;
        }
        return false;
    }

    private boolean overlapsBooking(List<Appointment> list, LocalDateTime start, LocalDateTime end, Long ignoreId) {
        for (Appointment a : list) {
            if (ignoreId != null && ignoreId.equals(a.getId())) continue;
            if (a.getStartAt().isBefore(end) && a.getEndAt().isAfter(start)) return true;
        }
        return false;
    }
}
