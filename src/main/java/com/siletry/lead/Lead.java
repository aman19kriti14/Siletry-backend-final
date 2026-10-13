package com.siletry.lead;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A demo request from the marketing site. Not tenant data: leads belong to Siletry itself,
 * which is why this does not extend BaseEntity (no clinic_id).
 */
@Entity
@Table(name = "leads", indexes = {
        @Index(name = "idx_leads_phone_created", columnList = "phone, created_at"),
        @Index(name = "idx_leads_status_created", columnList = "status, created_at"),
        @Index(name = "idx_leads_notify_due", columnList = "notify_status, notify_next_at")
})
@Getter
@Setter
public class Lead {

    public enum Status { NEW, CONTACTED, DEMO_SCHEDULED, CONVERTED, NOT_INTERESTED, SPAM }

    public enum NotifyStatus { PENDING, SENDING, SENT, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    /** Digits with country code: 919845012345 */
    @Column(nullable = false, length = 15)
    private String phone;

    @Column(nullable = false, length = 60)
    private String city;

    @Column(name = "clinic_name", length = 100)
    private String clinicName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.NEW;

    @Column(name = "status_note", length = 500)
    private String statusNote;

    // ---- where it came from ----
    @Column(length = 40)
    private String source;

    @Column(length = 200)
    private String page;

    @Column(name = "utm_source", length = 100)
    private String utmSource;

    @Column(name = "utm_medium", length = 100)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 100)
    private String utmCampaign;

    /** SHA-256 of the client IP with a salt. The raw IP is never stored. */
    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    /** Same person submitting again inside the duplicate window bumps this instead of adding a row. */
    @Column(name = "submission_count", nullable = false)
    private int submissionCount = 1;

    @Column(name = "last_submitted_at", nullable = false)
    private LocalDateTime lastSubmittedAt;

    // ---- WhatsApp alert to the team ----
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_status", nullable = false, length = 12)
    private NotifyStatus notifyStatus = NotifyStatus.PENDING;

    @Column(name = "notify_attempts", nullable = false)
    private int notifyAttempts;

    @Column(name = "notify_next_at")
    private LocalDateTime notifyNextAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "notify_message_id", length = 120)
    private String notifyMessageId;

    @Column(name = "notify_error", length = 500)
    private String notifyError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (lastSubmittedAt == null) lastSubmittedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
