package com.siletry.patient;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "patient",
        uniqueConstraints = @UniqueConstraint(name = "uk_patient_clinic_phone", columnNames = {"clinic_id", "phone"}),
        indexes = @Index(name = "idx_patient_clinic_name", columnList = "clinic_id,name"))
@Getter
@Setter
public class Patient extends BaseEntity {

    @Column(nullable = false)
    private String name;

    /** Normalized digits with country code: 919845012345 */
    @Column(nullable = false)
    private String phone;

    /** Clinic-facing ID, e.g. 40218. */
    @Column(name = "patient_no", nullable = false)
    private Long patientNo;

    private Integer age;

    /** Free text: Female / Male / Other */
    private String gender;

    @Column(name = "preferred_language")
    private String preferredLanguage;

    @Column(name = "preferred_doctor_id")
    private Long preferredDoctorId;

    @Column(name = "emergency_contact_name")
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone;

    /** Opted in to WhatsApp messages (recorded on first message or by staff). */
    @Column(name = "whatsapp_opt_in", nullable = false)
    private boolean whatsappOptIn = false;

    @Column(name = "opt_in_at")
    private LocalDateTime optInAt;

    /** Sent STOP. No business-initiated messages until they message again. */
    @Column(name = "opted_out", nullable = false)
    private boolean optedOut = false;

    @Column(length = 1000)
    private String notes;

    public String initials() {
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].isEmpty() ? "" : parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }
}
