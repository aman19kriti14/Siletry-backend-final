package com.siletry.doctor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DoctorLeaveRepository extends JpaRepository<DoctorLeave, Long> {

    @Query("select l from DoctorLeave l where l.doctorId = :doctorId and l.endAt > :from and l.startAt < :to order by l.startAt")
    List<DoctorLeave> findOverlapping(@Param("doctorId") Long doctorId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    @Query("select l from DoctorLeave l where l.doctorId = :doctorId and l.endAt > :now order by l.startAt")
    List<DoctorLeave> findUpcoming(@Param("doctorId") Long doctorId, @Param("now") LocalDateTime now);

    Optional<DoctorLeave> findByIdAndClinicId(Long id, Long clinicId);
}
