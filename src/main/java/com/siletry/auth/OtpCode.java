package com.siletry.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A one-time code sent on WhatsApp for login or signup. Only the hash is stored. */
@Entity
@Table(name = "otp_code", indexes = @Index(name = "idx_otp_phone", columnList = "phone,created_at"))
@Getter
@Setter
public class OtpCode {

    public enum Purpose { LOGIN, SIGNUP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Random public id the client sends back with the code. */
    @Column(nullable = false, unique = true, length = 40)
    private String ticket;

    @Column(nullable = false)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    /** LOGIN: the user id. SIGNUP: JSON with the signup form. */
    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
