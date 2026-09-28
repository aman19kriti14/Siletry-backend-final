package com.siletry.messaging;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Monthly message counter per clinic, for the message cap. */
@Entity
@Table(name = "message_usage",
        uniqueConstraints = @UniqueConstraint(name = "uk_usage_clinic_month", columnNames = {"clinic_id", "month"}))
@Getter
@Setter
public class MessageUsage extends BaseEntity {

    /** "2026-09" */
    @Column(nullable = false, length = 7)
    private String month;

    /** Paid template messages (outside the 24-hour window). Counts toward the cap. */
    @Column(name = "business_initiated", nullable = false)
    private int businessInitiated = 0;

    /** Free replies inside the 24-hour window. */
    @Column(name = "service_replies", nullable = false)
    private int serviceReplies = 0;

    @Column(nullable = false)
    private int inbound = 0;
}
