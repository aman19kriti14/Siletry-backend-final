package com.siletry.lead;

import com.siletry.ops.OpsNotifier;
import com.siletry.ops.OpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * Delivers the WhatsApp alert for each new lead.
 *
 * Flow: the lead is saved first (it is never lost), then an async listener sends the alert
 * after commit. If Meta is down or rejects the message, the retry job tries again with
 * exponential backoff. A database claim makes sure a lead is alerted at most once even
 * when the listener and the retry job run at the same moment.
 */
@Service
public class LeadNotificationService {

    private static final Logger log = LoggerFactory.getLogger(LeadNotificationService.class);
    private static final Duration SENDING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private static final Duration GIVE_UP_AFTER = Duration.ofDays(2);

    private final LeadRepository leads;
    private final OpsNotifier notifier;
    private final OpsProperties.Leads config;
    private final TransactionTemplate tx;

    public LeadNotificationService(LeadRepository leads, OpsNotifier notifier, OpsProperties props, TransactionTemplate tx) {
        this.leads = leads;
        this.notifier = notifier;
        this.config = props.leads();
        this.tx = tx;
    }

    @Async("opsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLeadCreated(LeadService.LeadCreatedEvent event) {
        deliver(event.leadId());
    }

    /** Picks up alerts that failed or got stuck (for example, the app restarted mid-send). */
    @Scheduled(fixedDelayString = "${siletry.ops.leads.retry-scan-ms:60000}", initialDelay = 30_000)
    public void retryDue() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> due = leads.findDueForNotify(now, config.maxNotifyAttempts(), now.minus(GIVE_UP_AFTER), PageRequest.of(0, 20));
        due.forEach(this::deliver);
    }

    /** Sends the alert for one lead if it can claim it. Safe to call from anywhere, any number of times. */
    public void deliver(Long leadId) {
        LocalDateTime now = LocalDateTime.now();
        Integer claimed = tx.execute(s -> leads.claimForNotify(leadId,
                EnumSet.of(Lead.NotifyStatus.PENDING, Lead.NotifyStatus.FAILED), now, now.plus(SENDING_TIMEOUT)));
        if (claimed == null || claimed == 0) return; // already sent, being sent, or skipped

        Lead lead = tx.execute(s -> leads.findById(leadId).orElse(null));
        if (lead == null) return;

        OpsNotifier.Result result;
        try {
            result = notifier.newLead(lead);
        } catch (RuntimeException e) {
            log.error("Lead {} alert threw", leadId, e);
            result = new OpsNotifier.Result(OpsNotifier.Outcome.FAILED, null, "Unexpected error: " + e.getClass().getSimpleName());
        }
        record(leadId, result);
    }

    void record(Long leadId, OpsNotifier.Result result) {
        tx.executeWithoutResult(s -> leads.findById(leadId).ifPresent(l -> {
            int attempts = l.getNotifyAttempts() + 1;
            l.setNotifyAttempts(attempts);
            l.setNotifyError(truncate(result.error()));
            switch (result.outcome()) {
                case SENT -> {
                    l.setNotifyStatus(Lead.NotifyStatus.SENT);
                    l.setNotifiedAt(LocalDateTime.now());
                    l.setNotifyMessageId(result.messageId());
                    l.setNotifyNextAt(null);
                    log.info("Lead {} alert sent ({})", leadId, result.messageId());
                }
                case NOT_CONFIGURED -> {
                    l.setNotifyStatus(Lead.NotifyStatus.SKIPPED);
                    l.setNotifyNextAt(null);
                    log.warn("Lead {} saved but no alert sent: alerts are not configured", leadId);
                }
                case FAILED -> {
                    boolean giveUp = attempts >= config.maxNotifyAttempts();
                    l.setNotifyStatus(Lead.NotifyStatus.FAILED);
                    l.setNotifyNextAt(giveUp ? null : LocalDateTime.now().plus(backoff(attempts)));
                    if (giveUp) {
                        log.error("Lead {} alert failed {} times, giving up: {}", leadId, attempts, result.error());
                    } else {
                        log.warn("Lead {} alert failed (attempt {}), retrying at {}: {}", leadId, attempts, l.getNotifyNextAt(), result.error());
                    }
                }
            }
        }));
    }

    /** 1, 2, 4, 8 ... minutes (from the configured base), capped at an hour. */
    Duration backoff(int attempts) {
        long factor = 1L << Math.min(attempts - 1, 10);
        Duration d = config.retryBaseDelay().multipliedBy(factor);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
