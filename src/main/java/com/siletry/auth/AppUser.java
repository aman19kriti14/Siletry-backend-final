package com.siletry.auth;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "app_user", indexes = @Index(name = "idx_user_clinic", columnList = "clinic_id"))
@Getter
@Setter
public class AppUser extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** Set when this login belongs to a doctor, so "my appointments" filters work. */
    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(nullable = false)
    private boolean active = true;
}
