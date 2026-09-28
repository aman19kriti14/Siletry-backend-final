package com.siletry.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByIdAndClinicId(Long id, Long clinicId);

    Optional<Conversation> findByClinicIdAndPatientId(Long clinicId, Long patientId);

    @Query("""
            select c from Conversation c
            where c.clinicId = :clinicId and c.state in :states
            order by c.emergency desc, c.lastMessageAt desc""")
    List<Conversation> findByStates(@Param("clinicId") Long clinicId,
                                    @Param("states") Collection<ConversationState> states,
                                    Pageable page);

    @Query("select c from Conversation c where c.clinicId = :clinicId and c.lastMessageAt is not null order by c.lastMessageAt desc")
    List<Conversation> findRecent(@Param("clinicId") Long clinicId, Pageable page);

    long countByClinicIdAndState(Long clinicId, ConversationState state);

    long countByClinicIdAndLastMessageAtIsNotNull(Long clinicId);

    @Query("select c.language, count(c) from Conversation c where c.clinicId = :clinicId and c.language is not null group by c.language")
    List<Object[]> languageCounts(@Param("clinicId") Long clinicId);

    List<Conversation> findByClinicIdAndPatientIdIn(Long clinicId, Collection<Long> patientIds);
}
