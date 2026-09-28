package com.siletry.messaging;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "message", indexes = {
        @Index(name = "idx_msg_conv", columnList = "conversation_id,id"),
        @Index(name = "idx_msg_clinic_created", columnList = "clinic_id,created_at")
})
@Getter
@Setter
public class Message extends BaseEntity {

    public enum Direction { IN, OUT }

    public enum Sender { PATIENT, SILETRY, STAFF, SYSTEM }

    public enum Kind { TEXT, BUTTONS, TEMPLATE }

    public enum Status { RECEIVED, QUEUED, SENT, DELIVERED, READ, FAILED }

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sender sender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.TEXT;

    @Column(columnDefinition = "text")
    private String body;

    /** Button labels offered with this message, separated by a newline. */
    @Column(columnDefinition = "text")
    private String buttons;

    @Column(name = "template_name")
    private String templateName;

    /** Provider message id. Unique so webhook retries don't create duplicates. */
    @Column(name = "external_id", unique = true)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(length = 500)
    private String error;

    /** True if sent outside the 24-hour window (a paid template). Counts toward the monthly cap. */
    @Column(name = "business_initiated", nullable = false)
    private boolean businessInitiated = false;

    @Column(name = "sent_by_user_id")
    private Long sentByUserId;

    private String language;

    @Column(name = "status_at")
    private LocalDateTime statusAt;
}
