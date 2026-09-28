package com.siletry.messaging;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One WhatsApp thread per patient per clinic. */
@Entity
@Table(name = "conversation",
        uniqueConstraints = @UniqueConstraint(name = "uk_conv_clinic_patient", columnNames = {"clinic_id", "patient_id"}),
        indexes = @Index(name = "idx_conv_clinic_state", columnList = "clinic_id,state,last_message_at"))
@Getter
@Setter
public class Conversation extends BaseEntity {

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationState state = ConversationState.BOT;

    @Column(nullable = false)
    private boolean emergency = false;

    /** Detected language of the patient's messages. */
    private String language;

    /** Short line for the "Needs a human" list, e.g. "Asked about an insurance claim". */
    @Column(name = "handoff_reason")
    private String handoffReason;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "last_message_preview", length = 300)
    private String lastMessagePreview;

    /** When the patient last wrote to us. Replies are free for 24 hours after this. */
    @Column(name = "last_inbound_at")
    private LocalDateTime lastInboundAt;

    @Column(name = "unread_count", nullable = false)
    private int unreadCount = 0;

    /** Set while staff own the thread; the bot stays silent. */
    @Column(name = "taken_over_by_user_id")
    private Long takenOverByUserId;

    @Column(name = "taken_over_at")
    private LocalDateTime takenOverAt;

    /** Bot's current step, e.g. PICK_SLOT. Null when idle. */
    @Column(name = "bot_step")
    private String botStep;

    /** JSON with the step's data (doctorId, offered options...). */
    @Column(name = "bot_context", columnDefinition = "text")
    private String botContext;

    @Column(name = "bot_step_at")
    private LocalDateTime botStepAt;

    public boolean isWindowOpen() {
        return lastInboundAt != null && lastInboundAt.isAfter(LocalDateTime.now().minusHours(24));
    }

    public boolean isTakenOver() {
        return takenOverByUserId != null;
    }
}
