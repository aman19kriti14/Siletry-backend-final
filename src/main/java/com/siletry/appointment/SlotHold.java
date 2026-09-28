package com.siletry.appointment;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A slot Siletry is offering to a patient on WhatsApp right now.
 * Staff see it as "Held by Siletry" and can't take it until it expires.
 */
@Entity
@Table(name = "slot_hold", indexes = @Index(name = "idx_hold_doctor", columnList = "doctor_id,start_at"))
@Getter
@Setter
public class SlotHold extends BaseEntity {

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Same format as Appointment.slotKey. Unique: one hold per slot. */
    @Column(name = "hold_key", nullable = false, unique = true)
    private String holdKey;

    /** Who holds it, e.g. "conv:42". */
    @Column(name = "holder_ref", nullable = false)
    private String holderRef;
}
