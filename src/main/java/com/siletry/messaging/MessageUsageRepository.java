package com.siletry.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MessageUsageRepository extends JpaRepository<MessageUsage, Long> {

    Optional<MessageUsage> findByClinicIdAndMonth(Long clinicId, String month);
}
