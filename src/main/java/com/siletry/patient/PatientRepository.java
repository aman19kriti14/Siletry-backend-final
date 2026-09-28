package com.siletry.patient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByIdAndClinicId(Long id, Long clinicId);

    Optional<Patient> findByClinicIdAndPhone(Long clinicId, String phone);

    @Query("""
            select p from Patient p
            where p.clinicId = :clinicId
              and (:q = '' or lower(p.name) like lower(concat('%', :q, '%'))
                   or p.phone like concat('%', :q, '%')
                   or str(p.patientNo) like concat('%', :q, '%'))""")
    Page<Patient> search(@Param("clinicId") Long clinicId, @Param("q") String q, Pageable page);

    long countByClinicId(Long clinicId);
}
