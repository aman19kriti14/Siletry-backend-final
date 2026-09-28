package com.siletry.doctor;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Leave or blocked time. Slots overlapping this are never offered. */
@Entity
@Table(name = "doctor_leave", indexes = @Index(name = "idx_leave_doctor", columnList = "doctor_id,start_at"))
@Getter
@Setter
public class DoctorLeave extends BaseEntity {

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    private String reason;
}
