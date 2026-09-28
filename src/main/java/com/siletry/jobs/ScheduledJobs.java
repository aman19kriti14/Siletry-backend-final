package com.siletry.jobs;

import com.siletry.appointment.Appointment;
import com.siletry.appointment.AppointmentRepository;
import com.siletry.appointment.SlotHoldRepository;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.followup.FollowUp;
import com.siletry.followup.FollowUpRepository;
import com.siletry.messaging.BotService;
import com.siletry.messaging.NotificationService;
import com.siletry.queue.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Background work. Runs inside the app (single Railway instance).
 * If you ever run more than one instance, add ShedLock so each job runs once.
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final SlotHoldRepository holds;
    private final AppointmentRepository appointments;
    private final ClinicRepository clinics;
    private final NotificationService notify;
    private final FollowUpRepository followUps;
    private final BotService bot;
    private final QueueService queue;

    public ScheduledJobs(SlotHoldRepository holds, AppointmentRepository appointments, ClinicRepository clinics,
                         NotificationService notify, FollowUpRepository followUps, BotService bot, QueueService queue) {
        this.holds = holds;
        this.appointments = appointments;
        this.clinics = clinics;
        this.notify = notify;
        this.followUps = followUps;
        this.bot = bot;
        this.queue = queue;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void purgeExpiredHolds() {
        int n = holds.deleteExpired(LocalDateTime.now());
        if (n > 0) log.debug("Released {} expired slot holds", n);
    }

    /** Evening-before reminders, sent at each clinic's reminder time (default 6 pm). */
    @Scheduled(fixedDelay = 5 * 60_000, initialDelay = 60_000)
    @Transactional
    public void sendReminders() {
        runReminders();
    }

    @Transactional
    public int runReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<Appointment> due = appointments.findNeedingReminder(tomorrow.atStartOfDay(), tomorrow.plusDays(1).atStartOfDay());
        Map<Long, Clinic> clinicCache = new HashMap<>();
        LocalTime now = LocalTime.now();
        int sent = 0;
        for (Appointment a : due) {
            Clinic c = clinicCache.computeIfAbsent(a.getClinicId(), id -> clinics.findById(id).orElse(null));
            if (c == null || !c.isRemindersEnabled() || now.isBefore(c.getReminderTime())) continue;
            try {
                notify.reminder(a);
                a.setReminderSentAt(LocalDateTime.now());
                sent++;
            } catch (Exception e) {
                log.warn("Reminder failed for appointment {}: {}", a.getId(), e.getMessage());
            }
        }
        if (sent > 0) log.info("Sent {} reminders", sent);
        return sent;
    }

    /** Follow-ups due today: message the patient with two open slots. Runs 9 am to 7 pm. */
    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 90_000)
    @Transactional
    public void sendFollowUps() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(19, 0))) return;
        runFollowUps();
    }

    @Transactional
    public int runFollowUps() {
        LocalDate today = LocalDate.now();
        int sent = 0;
        for (FollowUp f : followUps.findToNotify(today, today.minusDays(3))) {
            try {
                if (bot.offerFollowUp(f)) sent++;
                else f.setNotifiedAt(LocalDateTime.now()); // don't retry every 15 minutes
            } catch (Exception e) {
                log.warn("Follow-up {} failed: {}", f.getId(), e.getMessage());
            }
        }
        if (sent > 0) log.info("Sent {} follow-up offers", sent);
        return sent;
    }

    /** Nudge when nobody has tapped "Call next" for twice the average consult time. */
    @Scheduled(fixedDelay = 5 * 60_000, initialDelay = 120_000)
    public void nudgeStaleQueues() {
        try {
            queue.nudgeStaleQueues();
        } catch (Exception e) {
            log.warn("Queue nudge failed: {}", e.getMessage());
        }
    }
}
