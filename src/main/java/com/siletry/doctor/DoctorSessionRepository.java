package com.siletry.doctor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface DoctorSessionRepository extends JpaRepository<DoctorSession, Long> {

    List<DoctorSession> findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(Long doctorId);

    List<DoctorSession> findByDoctorIdAndDayOfWeekOrderByStartTimeAsc(Long doctorId, DayOfWeek day);

    void deleteByDoctorId(Long doctorId);
}
