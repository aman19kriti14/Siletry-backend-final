package com.siletry.qr;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A printed QR code. Scanning opens WhatsApp with a prefilled message containing the code,
 * e.g. "[BK-POSTER]", so every booking/check-in can be traced to where it came from.
 */
@Entity
@Table(name = "qr_code",
        uniqueConstraints = @UniqueConstraint(name = "uk_qr_clinic_code", columnNames = {"clinic_id", "code"}))
@Getter
@Setter
public class QrCode extends BaseEntity {

    public enum Type { BOOKING, CHECKIN }

    /** BK-POSTER, BK-RX, CHECKIN-DESK ... */
    @Column(nullable = false, length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    /** Where it's placed: "Poster outside clinic". */
    @Column(nullable = false)
    private String label;

    /** Optional: send straight to this doctor's slots. */
    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(nullable = false)
    private long scans = 0;

    @Column(nullable = false)
    private boolean active = true;
}
