package com.siletry.doctor;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** One working block, e.g. Monday 9:30 to 13:00. A day can have several. */
@Entity
@Table(name = "doctor_session", indexes = @Index(name = "idx_session_doctor", columnList = "doctor_id"))
@Getter
@Setter
public class DoctorSession extends BaseEntity {

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
}
