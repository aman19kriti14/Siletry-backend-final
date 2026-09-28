package com.siletry.qr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QrCodeRepository extends JpaRepository<QrCode, Long> {

    List<QrCode> findByClinicIdOrderByTypeAscCodeAsc(Long clinicId);

    Optional<QrCode> findByClinicIdAndCode(Long clinicId, String code);

    Optional<QrCode> findByIdAndClinicId(Long id, Long clinicId);
}
