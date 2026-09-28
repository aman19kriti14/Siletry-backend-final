package com.siletry.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    boolean existsByExternalId(String externalId);

    Optional<Message> findByExternalId(String externalId);

    @Query("select m from Message m where m.conversationId = :convId order by m.id desc")
    List<Message> findLatest(@Param("convId") Long conversationId, Pageable page);

    @Query("select m from Message m where m.conversationId = :convId and m.id > :afterId order by m.id asc")
    List<Message> findAfter(@Param("convId") Long conversationId, @Param("afterId") Long afterId);

    @Query("""
            select count(m) from Message m
            where m.clinicId = :clinicId and m.direction = :direction and m.sender = :sender
              and m.status <> :excluded and m.createdAt >= :from and m.createdAt < :to""")
    long countOutbound(@Param("clinicId") Long clinicId,
                       @Param("direction") Message.Direction direction,
                       @Param("sender") Message.Sender sender,
                       @Param("excluded") Message.Status excluded,
                       @Param("from") LocalDateTime from,
                       @Param("to") LocalDateTime to);

    /** Messages Siletry sent (successfully) in the period. */
    default long countAnsweredBySiletry(Long clinicId, LocalDateTime from, LocalDateTime to) {
        return countOutbound(clinicId, Message.Direction.OUT, Message.Sender.SILETRY, Message.Status.FAILED, from, to);
    }
}
