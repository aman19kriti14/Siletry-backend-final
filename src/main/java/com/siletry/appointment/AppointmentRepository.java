package com.siletry.appointment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByIdAndClinicId(Long id, Long clinicId);

    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId and a.startAt >= :from and a.startAt < :to
            order by a.startAt asc, a.id asc""")
    List<Appointment> findInRange(@Param("clinicId") Long clinicId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    @Query("""
            select a from Appointment a
            where a.doctorId = :doctorId and a.startAt < :to and a.endAt > :from
              and a.status in :statuses and a.slotKey is not null""")
    List<Appointment> findSlottedOverlapping(@Param("doctorId") Long doctorId,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to,
                                             @Param("statuses") Collection<AppointmentStatus> statuses);

    @Query("""
            select a from Appointment a
            where a.doctorId = :doctorId and a.startAt >= :from and a.startAt < :to
            order by a.startAt asc""")
    List<Appointment> findForDoctorInRange(@Param("doctorId") Long doctorId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);

    List<Appointment> findByClinicIdAndPatientIdOrderByStartAtDesc(Long clinicId, Long patientId);

    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId and a.patientId = :patientId
              and a.status = com.siletry.appointment.AppointmentStatus.CONFIRMED and a.startAt >= :now
            order by a.startAt asc""")
    List<Appointment> findUpcomingForPatient(@Param("clinicId") Long clinicId,
                                             @Param("patientId") Long patientId,
                                             @Param("now") LocalDateTime now);

    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId and a.patientId in :patientIds
              and a.status <> com.siletry.appointment.AppointmentStatus.CANCELLED""")
    List<Appointment> findForPatients(@Param("clinicId") Long clinicId,
                                      @Param("patientIds") Collection<Long> patientIds);

    /** Queue for one doctor on one day (checked-in patients). */
    @Query("""
            select a from Appointment a
            where a.doctorId = :doctorId and a.checkedInAt >= :dayStart and a.checkedInAt < :dayEnd
              and a.status in :statuses
            order by a.emergency desc, a.queueOrder asc, a.id asc""")
    List<Appointment> findQueue(@Param("doctorId") Long doctorId,
                                @Param("dayStart") LocalDateTime dayStart,
                                @Param("dayEnd") LocalDateTime dayEnd,
                                @Param("statuses") Collection<AppointmentStatus> statuses);

    @Query("""
            select max(a.tokenNumber) from Appointment a
            where a.clinicId = :clinicId and a.checkedInAt >= :dayStart and a.checkedInAt < :dayEnd""")
    Integer maxTokenForDay(@Param("clinicId") Long clinicId,
                           @Param("dayStart") LocalDateTime dayStart,
                           @Param("dayEnd") LocalDateTime dayEnd);

    @Query("""
            select coalesce(max(a.queueOrder), 0) from Appointment a
            where a.doctorId = :doctorId and a.checkedInAt >= :dayStart and a.checkedInAt < :dayEnd""")
    Long maxQueueOrder(@Param("doctorId") Long doctorId,
                       @Param("dayStart") LocalDateTime dayStart,
                       @Param("dayEnd") LocalDateTime dayEnd);

    @Query("""
            select coalesce(min(a.queueOrder), 0) from Appointment a
            where a.doctorId = :doctorId and a.checkedInAt >= :dayStart and a.checkedInAt < :dayEnd""")
    Long minQueueOrder(@Param("doctorId") Long doctorId,
                       @Param("dayStart") LocalDateTime dayStart,
                       @Param("dayEnd") LocalDateTime dayEnd);

    /** Recent completed consults, for the doctor's real average consult time. */
    @Query("""
            select a from Appointment a
            where a.doctorId = :doctorId and a.status = com.siletry.appointment.AppointmentStatus.SEEN
              and a.calledAt is not null and a.completedAt is not null
            order by a.completedAt desc""")
    List<Appointment> findRecentCompleted(@Param("doctorId") Long doctorId,
                                          org.springframework.data.domain.Pageable page);

    /** Due reminders: confirmed, starting in the window, reminder not yet sent. */
    @Query("""
            select a from Appointment a
            where a.status = com.siletry.appointment.AppointmentStatus.CONFIRMED
              and a.remindEveningBefore = true and a.reminderSentAt is null
              and a.startAt >= :from and a.startAt < :to""")
    List<Appointment> findNeedingReminder(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            select a from Appointment a
            where a.status = com.siletry.appointment.AppointmentStatus.IN_CONSULT
              and a.calledAt is not null and a.calledAt >= :dayStart""")
    List<Appointment> findAllInConsultSince(@Param("dayStart") LocalDateTime dayStart);

    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId and a.createdAt >= :from and a.createdAt < :to""")
    List<Appointment> findCreatedInRange(@Param("clinicId") Long clinicId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    @Query("""
            select count(a) from Appointment a
            where a.doctorId = :doctorId and a.bookedBy = com.siletry.appointment.BookedBy.SILETRY
              and a.status <> com.siletry.appointment.AppointmentStatus.CANCELLED
              and a.startAt >= :dayStart and a.startAt < :dayEnd""")
    long countBotBookingsForDay(@Param("doctorId") Long doctorId,
                                @Param("dayStart") LocalDateTime dayStart,
                                @Param("dayEnd") LocalDateTime dayEnd);
}
