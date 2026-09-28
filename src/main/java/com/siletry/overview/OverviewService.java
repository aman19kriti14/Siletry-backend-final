package com.siletry.overview;

import com.siletry.appointment.Appointment;
import com.siletry.appointment.AppointmentRepository;
import com.siletry.appointment.AppointmentStatus;
import com.siletry.appointment.BookedBy;
import com.siletry.auth.CurrentUser;
import com.siletry.messaging.ConversationRepository;
import com.siletry.messaging.InboxService;
import com.siletry.messaging.InboxService.ConversationRow;
import com.siletry.messaging.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Numbers for the Overview page. All real, from the database. */
@Service
public class OverviewService {

    public record Metric(long value, Double changePercent) {}

    public record BookingsMetric(long value, Double changePercent, int percentWithoutStaff) {}

    public record RevenueMetric(long value, long changeVsPrevious) {}

    /** Rates in percent. Null when there's not enough data yet: show "—" rather than a made-up number. */
    public record NoShowMetric(Double ratePercent, Double previousRatePercent, Double changePercent, long noShows) {}

    public record DayBar(LocalDate date, String label, long siletry, long staff) {}

    public record LanguageShare(String language, long conversations, int percent) {}

    public record OverviewView(LocalDate from, LocalDate to, Metric messagesAnswered, BookingsMetric appointmentsBooked,
                               RevenueMetric revenueBooked, NoShowMetric noShows, List<DayBar> bookingsPerDay,
                               List<ConversationRow> needsHuman, long needsHumanCount, List<LanguageShare> languages) {}

    private final MessageRepository messages;
    private final AppointmentRepository appointments;
    private final ConversationRepository conversations;
    private final InboxService inbox;

    public OverviewService(MessageRepository messages, AppointmentRepository appointments,
                           ConversationRepository conversations, InboxService inbox) {
        this.messages = messages;
        this.appointments = appointments;
        this.conversations = conversations;
        this.inbox = inbox;
    }

    @Transactional(readOnly = true)
    public OverviewView overview(LocalDate from, LocalDate to) {
        Long clinicId = CurrentUser.clinicId();
        LocalDate start = from != null ? from : LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = to != null ? to : start.plusDays(6);
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDateTime cFrom = start.atStartOfDay();
        LocalDateTime cTo = end.plusDays(1).atStartOfDay();
        LocalDateTime pFrom = cFrom.minusDays(days);
        LocalDateTime pTo = cFrom;

        // Messages Siletry answered
        long msgs = messages.countAnsweredBySiletry(clinicId, cFrom, cTo);
        long prevMsgs = messages.countAnsweredBySiletry(clinicId, pFrom, pTo);

        // Bookings made in the period (by creation time), excluding cancelled
        List<Appointment> created = appointments.findCreatedInRange(clinicId, cFrom, cTo).stream()
                .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).toList();
        List<Appointment> prevCreated = appointments.findCreatedInRange(clinicId, pFrom, pTo).stream()
                .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).toList();
        long bySiletry = created.stream().filter(a -> a.getBookedBy() == BookedBy.SILETRY).count();
        int pctNoStaff = created.isEmpty() ? 0 : (int) Math.round(100.0 * bySiletry / created.size());

        long revenue = created.stream().mapToLong(Appointment::getFeeAtBooking).sum();
        long prevRevenue = prevCreated.stream().mapToLong(Appointment::getFeeAtBooking).sum();

        // No-shows among visits that happened in the period
        List<Appointment> visits = appointments.findInRange(clinicId, cFrom, cTo);
        List<Appointment> prevVisits = appointments.findInRange(clinicId, pFrom, pTo);
        Double rate = noShowRate(visits);
        Double prevRate = noShowRate(prevVisits);
        Double rateChange = rate == null || prevRate == null || prevRate == 0 ? null : round1(100.0 * (rate - prevRate) / prevRate);
        long noShowCount = visits.stream().filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).count();

        // Stacked bars per day
        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) perDay.put(d, new long[2]);
        for (Appointment a : created) {
            long[] bucket = perDay.get(a.getCreatedAt().toLocalDate());
            if (bucket == null) continue;
            if (a.getBookedBy() == BookedBy.SILETRY) bucket[0]++; else bucket[1]++;
        }
        List<DayBar> bars = perDay.entrySet().stream().map(e -> new DayBar(e.getKey(),
                e.getKey().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                e.getValue()[0], e.getValue()[1])).toList();

        // Languages
        List<Object[]> langRows = conversations.languageCounts(clinicId);
        long langTotal = langRows.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        List<LanguageShare> langs = langRows.stream()
                .map(r -> new LanguageShare((String) r[0], ((Number) r[1]).longValue(),
                        langTotal == 0 ? 0 : (int) Math.round(100.0 * ((Number) r[1]).longValue() / langTotal)))
                .sorted(Comparator.comparingLong(LanguageShare::conversations).reversed())
                .toList();

        List<ConversationRow> needs = inbox.needsHuman(clinicId, 5);
        long needsCount = conversations.countByClinicIdAndState(clinicId, com.siletry.messaging.ConversationState.NEEDS_YOU);

        return new OverviewView(start, end,
                new Metric(msgs, change(msgs, prevMsgs)),
                new BookingsMetric(created.size(), change(created.size(), prevCreated.size()), pctNoStaff),
                new RevenueMetric(revenue, revenue - prevRevenue),
                new NoShowMetric(rate, prevRate, rateChange, noShowCount),
                bars, needs, needsCount, langs);
    }

    private Double noShowRate(List<Appointment> list) {
        long seen = list.stream().filter(a -> a.getStatus() == AppointmentStatus.SEEN).count();
        long noShow = list.stream().filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).count();
        long total = seen + noShow;
        return total < 5 ? null : round1(100.0 * noShow / total);
    }

    private Double change(long now, long before) {
        return before == 0 ? null : round1(100.0 * (now - before) / before);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
