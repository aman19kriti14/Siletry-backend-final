package com.siletry.messaging;

import java.util.List;

/**
 * The WhatsApp templates Siletry needs in every clinic's account, with the exact wording Meta approves.
 * The same definitions are used to submit them, to seed the Templates page, and to fill the parameters when sending.
 * Keep the wording specific: templates that are mostly variables get rejected.
 */
public final class TemplateCatalog {

    private TemplateCatalog() {}

    public record Spec(String name, String category, String body, List<String> example, List<String> quickReplies) {}

    public static final Spec BOOKING_CONFIRMED = new Spec("booking_confirmed", "UTILITY",
            "Your appointment with {{1}} is confirmed for {{2}} at {{3}}. Reply CHANGE to reschedule or CANCEL to cancel.",
            List.of("Dr. Anitha Rao", "tomorrow at 5:30 pm", "Nandini Clinic, Jayanagar"), List.of());

    public static final Spec APPOINTMENT_RESCHEDULED = new Spec("appointment_rescheduled", "UTILITY",
            "Your appointment has been moved. {{1}} will now see you {{2}} at {{3}}.",
            List.of("Dr. Anitha Rao", "tomorrow at 5:30 pm", "Nandini Clinic, Jayanagar"), List.of());

    public static final Spec APPOINTMENT_CANCELLED = new Spec("appointment_cancelled", "UTILITY",
            "Your appointment with {{1}} {{2}} has been cancelled by the clinic. Reply to this message to book another time.",
            List.of("Dr. Anitha Rao", "tomorrow at 5:30 pm"), List.of());

    public static final Spec APPOINTMENT_REMINDER = new Spec("appointment_reminder", "UTILITY",
            "Reminder: you have an appointment with {{1}} {{2}} at {{3}}. Please confirm you're coming.",
            List.of("Dr. Anitha Rao", "tomorrow at 5:30 pm", "Nandini Clinic, Jayanagar"), List.of("YES", "CHANGE", "CANCEL"));

    public static final Spec QUEUE_CHECKED_IN = new Spec("queue_checked_in", "UTILITY",
            "You're checked in at {{1}}. Your token number is {{2}}. We'll message you here when it's your turn.",
            List.of("Nandini Clinic", "14"), List.of());

    public static final Spec QUEUE_YOUR_TURN = new Spec("queue_your_turn", "UTILITY",
            "Token {{1}}: it's your turn. Please go in to see {{2}} now.",
            List.of("14", "Dr. Anitha Rao"), List.of());

    public static final Spec QUEUE_NEXT = new Spec("queue_next", "UTILITY",
            "Token {{1}}: you're next. Please be near the consultation room.",
            List.of("15"), List.of());

    public static final Spec DOCTOR_DELAY = new Spec("doctor_delay", "UTILITY",
            "{{1}} is running about {{2}} minutes late today. Sorry for the wait, we'll see you as soon as we can.",
            List.of("Dr. Anitha Rao", "30"), List.of());

    public static final Spec FOLLOWUP_DUE = new Spec("followup_due", "UTILITY",
            "It's time for your {{1}} with {{2}}. Reply to this message and we'll find a slot that suits you.",
            List.of("BP review", "Dr. Anitha Rao"), List.of());

    /** Everything a clinic account needs. */
    public static final List<Spec> CLINIC = List.of(BOOKING_CONFIRMED, APPOINTMENT_RESCHEDULED, APPOINTMENT_CANCELLED,
            APPOINTMENT_REMINDER, QUEUE_CHECKED_IN, QUEUE_YOUR_TURN, QUEUE_NEXT, DOCTOR_DELAY, FOLLOWUP_DUE);

    // ----- On Siletry's own account (create these once in your WhatsApp Manager) -----

    /** AUTHENTICATION template with a copy-code button. Meta fixes the wording: "{{1}} is your verification code." */
    public static final String LOGIN_CODE = "siletry_login_code";

    /** For doctor alerts when the doctor hasn't messaged Siletry in 24 hours. */
    public static final Spec STAFF_ALERT = new Spec("siletry_staff_alert", "UTILITY",
            "Siletry update for {{1}}: {{2}} Reply to this message to respond.",
            List.of("Nandini Clinic", "Imran Sheikh (+91 98450 22114) reported chest pain. Please call them now."), List.of());

    public static Spec byName(String name) {
        return CLINIC.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }
}
