package com.siletry.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {

    Optional<OtpCode> findByTicket(String ticket);

    long countByPhoneAndCreatedAtAfter(String phone, LocalDateTime after);

    Optional<OtpCode> findFirstByPhoneOrderByCreatedAtDesc(String phone);
}
