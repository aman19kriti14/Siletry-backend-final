package com.siletry.appointment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SlotHoldRepository extends JpaRepository<SlotHold, Long> {

    Optional<SlotHold> findByHoldKey(String holdKey);

    @Query("select h from SlotHold h where h.doctorId = :doctorId and h.startAt >= :from and h.startAt < :to and h.expiresAt > :now")
    List<SlotHold> findActive(@Param("doctorId") Long doctorId,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from SlotHold h where h.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from SlotHold h where h.holderRef = :holderRef")
    int deleteByHolder(@Param("holderRef") String holderRef);
}
