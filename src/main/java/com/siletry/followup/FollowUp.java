package com.siletry.followup;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** "BP review, due this week". Siletry messages the patient on the due date with two open slots. */
@Entity
@Table(name = "follow_up", indexes = {
        @Index(name = "idx_followup_patient", columnList = "clinic_id,patient_id"),
        @Index(name = "idx_followup_due", columnList = "status,due_date")
})
@Getter
@Setter
public class FollowUp extends BaseEntity {

    public enum Status { DUE, OFFERED, BOOKED, DONE, CANCELLED }

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(nullable = false)
    private String title;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DUE;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "appointment_id")
    private Long appointmentId;

    public boolean isOpen() {
        return status == Status.DUE || status == Status.OFFERED;
    }
}
