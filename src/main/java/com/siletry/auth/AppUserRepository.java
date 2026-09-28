package com.siletry.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findByClinicIdOrderByNameAsc(Long clinicId);

    Optional<AppUser> findByIdAndClinicId(Long id, Long clinicId);

    List<AppUser> findByPhoneAndActiveTrue(String phone);
}
