package com.siletry.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    List<MessageTemplate> findByClinicIdOrderByNameAsc(Long clinicId);

    Optional<MessageTemplate> findByIdAndClinicId(Long id, Long clinicId);
}
