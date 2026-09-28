package com.siletry.doctor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    List<Doctor> findByClinicIdOrderByNameAsc(Long clinicId);

    List<Doctor> findByClinicIdAndActiveTrueOrderByNameAsc(Long clinicId);

    Optional<Doctor> findByIdAndClinicId(Long id, Long clinicId);

    List<Doctor> findByIdIn(Collection<Long> ids);

    List<Doctor> findByAlertMobileAndActiveTrue(String alertMobile);
}
