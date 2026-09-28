package com.siletry.followup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    Collection<FollowUp.Status> OPEN = EnumSet.of(FollowUp.Status.DUE, FollowUp.Status.OFFERED);

    Optional<FollowUp> findByIdAndClinicId(Long id, Long clinicId);

    List<FollowUp> findByClinicIdAndPatientIdOrderByDueDateAsc(Long clinicId, Long patientId);

    @Query("""
            select f from FollowUp f
            where f.clinicId = :clinicId and f.patientId in :patientIds and f.status in :statuses
            order by f.dueDate asc""")
    List<FollowUp> findForPatientsWithStatus(@Param("clinicId") Long clinicId,
                                             @Param("patientIds") Collection<Long> patientIds,
                                             @Param("statuses") Collection<FollowUp.Status> statuses);

    default List<FollowUp> findOpenForPatients(Long clinicId, Collection<Long> patientIds) {
        return findForPatientsWithStatus(clinicId, patientIds, OPEN);
    }

    @Query("""
            select f from FollowUp f
            where f.status = :status and f.notifiedAt is null
              and f.dueDate <= :today and f.dueDate >= :oldest""")
    List<FollowUp> findUnnotified(@Param("status") FollowUp.Status status,
                                  @Param("today") LocalDate today,
                                  @Param("oldest") LocalDate oldest);

    /** Due today (or a little overdue) and not messaged yet. */
    default List<FollowUp> findToNotify(LocalDate today, LocalDate oldest) {
        return findUnnotified(FollowUp.Status.DUE, today, oldest);
    }

    @Query("""
            select f from FollowUp f
            where f.clinicId = :clinicId and f.status in :statuses and f.dueDate <= :until
            order by f.dueDate asc""")
    List<FollowUp> findWithStatusDueBy(@Param("clinicId") Long clinicId,
                                       @Param("statuses") Collection<FollowUp.Status> statuses,
                                       @Param("until") LocalDate until);

    default List<FollowUp> findOpenDueBy(Long clinicId, LocalDate until) {
        return findWithStatusDueBy(clinicId, OPEN, until);
    }
}
