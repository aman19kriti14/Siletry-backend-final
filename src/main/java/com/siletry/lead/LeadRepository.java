package com.siletry.lead;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LeadRepository extends JpaRepository<Lead, Long> {

    Optional<Lead> findFirstByPhoneAndCreatedAtAfterOrderByCreatedAtDesc(String phone, LocalDateTime after);

    Page<Lead> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Lead> findByStatusOrderByCreatedAtDesc(Lead.Status status, Pageable pageable);

    /**
     * Atomically claims a lead for sending so the async sender and the retry job can never
     * alert twice. Returns 1 if this caller won the claim.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Lead l
               set l.notifyStatus = com.siletry.lead.Lead.NotifyStatus.SENDING,
                   l.notifyNextAt = :staleAfter,
                   l.version = l.version + 1
             where l.id = :id
               and (l.notifyStatus in :claimable
                    or (l.notifyStatus = com.siletry.lead.Lead.NotifyStatus.SENDING and l.notifyNextAt < :now))
            """)
    int claimForNotify(@Param("id") Long id,
                       @Param("claimable") Collection<Lead.NotifyStatus> claimable,
                       @Param("now") LocalDateTime now,
                       @Param("staleAfter") LocalDateTime staleAfter);

    /** Failed or stuck alerts whose retry time has come. */
    @Query("""
            select l.id from Lead l
             where l.notifyStatus in (com.siletry.lead.Lead.NotifyStatus.PENDING,
                                      com.siletry.lead.Lead.NotifyStatus.FAILED,
                                      com.siletry.lead.Lead.NotifyStatus.SENDING)
               and l.notifyAttempts < :maxAttempts
               and (l.notifyNextAt is null or l.notifyNextAt <= :now)
               and l.createdAt > :notBefore
             order by l.createdAt
            """)
    List<Long> findDueForNotify(@Param("now") LocalDateTime now,
                                @Param("maxAttempts") int maxAttempts,
                                @Param("notBefore") LocalDateTime notBefore,
                                Pageable pageable);
}
