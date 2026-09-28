package com.siletry.doctor;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "doctor", indexes = @Index(name = "idx_doctor_clinic", columnList = "clinic_id"))
@Getter
@Setter
public class Doctor extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String speciality;

    @Column(name = "registration_no")
    private String registrationNo;

    /** Consultation fee in rupees. */
    @Column(nullable = false)
    private int fee = 0;

    /** Doctor's own mobile for emergency escalations and the daily digest. */
    @Column(name = "alert_mobile")
    private String alertMobile;

    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes = 15;

    /** Comma separated language codes: en,kn,hi */
    @Column(nullable = false)
    private String languages = "en";

    @Column(nullable = false)
    private boolean active = true;

    // ----- Booking rules -----
    /** Siletry won't offer a slot starting sooner than this. */
    @Column(name = "min_notice_minutes", nullable = false)
    private int minNoticeMinutes = 30;

    /** How far ahead patients can book on WhatsApp. */
    @Column(name = "max_advance_days", nullable = false)
    private int maxAdvanceDays = 14;

    /** Optional cap on WhatsApp bookings per day (null = no cap). */
    @Column(name = "max_bot_bookings_per_day")
    private Integer maxBotBookingsPerDay;

    @Column(name = "bookable_on_whatsapp", nullable = false)
    private boolean bookableOnWhatsapp = true;

    public List<String> languageList() {
        return Arrays.stream(languages.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** "Dr. Anitha Rao" -> "Dr. A. Rao" */
    public String shortName() {
        String n = name.replaceFirst("(?i)^dr\\.?\\s*", "").trim();
        String[] parts = n.split("\\s+");
        if (parts.length < 2) return "Dr. " + n;
        StringBuilder sb = new StringBuilder("Dr. ");
        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(". ");
        }
        return sb.append(parts[parts.length - 1]).toString();
    }

    public String displayName() {
        return name.toLowerCase().startsWith("dr") ? name : "Dr. " + name;
    }

    public String initials() {
        String n = name.replaceFirst("(?i)^dr\\.?\\s*", "").trim();
        String[] parts = n.split("\\s+");
        String first = parts[0].isEmpty() ? "" : parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }
}
