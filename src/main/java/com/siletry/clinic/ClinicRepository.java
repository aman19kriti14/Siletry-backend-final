package com.siletry.clinic;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ClinicRepository extends JpaRepository<Clinic, Long> {

    Optional<Clinic> findByWhatsappNumber(String whatsappNumber);

    Optional<Clinic> findByWaPhoneNumberId(String waPhoneNumberId);

    /** Row lock used when handing out per-clinic sequence numbers (patient IDs, tokens). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Clinic c where c.id = :id")
    Optional<Clinic> findByIdForUpdate(@Param("id") Long id);
}
