package com.siletry.clinic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "clinic")
@Getter
@Setter
public class Clinic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Short locality shown in the header, e.g. "Jayanagar". */
    private String area;
    private String city;
    private String address;
    private String phone;

    /** The clinic's own WhatsApp number (coexistence), normalized digits. */
    @Column(name = "whatsapp_number", unique = true)
    private String whatsappNumber;

    @Column(name = "whatsapp_connected", nullable = false)
    private boolean whatsappConnected = false;

    /** Provider-side reference (Gupshup app id etc.). */
    @Column(name = "whatsapp_provider_ref")
    private String whatsappProviderRef;

    /** "Siletry active" toggle: when false the bot stays silent and everything goes to the inbox. */
    @Column(name = "bot_enabled", nullable = false)
    private boolean botEnabled = true;

    @Column(nullable = false)
    private String timezone = "Asia/Kolkata";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan = Plan.TRIAL;

    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;

    /** Monthly cap on business-initiated (template) messages. */
    @Column(name = "message_cap", nullable = false)
    private int messageCap = 1500;

    /** Per-clinic running patient number, shown as "ID 40218". */
    @Column(name = "next_patient_no", nullable = false)
    private long nextPatientNo = 40001;

    // ----- Automations -----
    @Column(name = "reminders_enabled", nullable = false)
    private boolean remindersEnabled = true;

    @Column(name = "reminder_time", nullable = false)
    private LocalTime reminderTime = LocalTime.of(18, 0);

    @Column(name = "followups_enabled", nullable = false)
    private boolean followupsEnabled = true;

    @Column(name = "queue_updates_enabled", nullable = false)
    private boolean queueUpdatesEnabled = true;

    @Column(name = "google_review_link")
    private String googleReviewLink;

    // ----- Onboarding (nullable so existing rows survive ddl-auto) -----
    @Column(name = "clinic_type")
    private String clinicType;

    /** "Above Apollo Pharmacy", added to confirmations so patients find the clinic. */
    private String landmark;

    /** Comma separated language codes Siletry should reply in. */
    @Column(name = "reply_languages")
    private String replyLanguages;

    @Column(name = "doctor_count_hint")
    private Integer doctorCountHint;

    /** 1..5 while setting up, 6 when live. Null = clinic created before onboarding existed (treated as live). */
    @Column(name = "onboarding_step")
    private Integer onboardingStep;

    @Column(name = "live_at")
    private LocalDateTime liveAt;

    /** CURRENT (keep existing number) or NEW (we issue one). */
    @Column(name = "whatsapp_number_choice")
    private String whatsappNumberChoice;

    @Column(name = "delay_announcements_enabled")
    private Boolean delayAnnouncementsEnabled;

    @Column(name = "google_reviews_enabled")
    private Boolean googleReviewsEnabled;

    // ----- WhatsApp Cloud API (the clinic's own Meta account) -----
    @Column(name = "wa_phone_number_id", unique = true)
    private String waPhoneNumberId;

    @Column(name = "wa_business_account_id")
    private String waBusinessAccountId;

    /** Encrypted with CredentialCipher. */
    @Column(name = "wa_access_token", columnDefinition = "text")
    private String waAccessToken;

    /** Encrypted. Used to check webhook signatures from the clinic's Meta app. */
    @Column(name = "wa_app_secret", columnDefinition = "text")
    private String waAppSecret;

    @Column(name = "wa_verified_name")
    private String waVerifiedName;

    @Column(name = "wa_connected_at")
    private LocalDateTime waConnectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** True when real messages go out through Meta for this clinic. */
    public boolean hasWhatsAppApi() {
        return waPhoneNumberId != null && waAccessToken != null;
    }

    public boolean isLive() {
        return liveAt != null || onboardingStep == null;
    }

    public int currentStep() {
        return onboardingStep == null ? 6 : onboardingStep;
    }

    public boolean delayAnnouncementsOn() {
        return delayAnnouncementsEnabled == null || delayAnnouncementsEnabled;
    }

    public boolean googleReviewsOn() {
        return googleReviewsEnabled != null && googleReviewsEnabled;
    }

    public java.util.List<String> replyLanguageList() {
        if (replyLanguages == null || replyLanguages.isBlank()) return java.util.List.of("en");
        return java.util.Arrays.stream(replyLanguages.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /** "Nandini Clinic, Jayanagar (Above Apollo Pharmacy)" */
    public String locationLine() {
        String base = displayName();
        return landmark == null || landmark.isBlank() ? base : base + " (" + landmark.trim() + ")";
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (trialEndsAt == null) trialEndsAt = createdAt.plusDays(7);
    }

    public enum Plan { TRIAL, ACTIVE, PAUSED }

    public String displayName() {
        return area == null || area.isBlank() ? name : name + ", " + area;
    }
}
