package com.siletry.messaging;

import com.siletry.clinic.Clinic;
import com.siletry.common.Fmt;
import com.siletry.doctor.Doctor;

import java.time.LocalDateTime;

/**
 * Everything the bot says, in one place so it's easy to reword or translate later.
 * English only for v1; patient language is stored so Kannada/Hindi versions can slot in here.
 */
public final class MessageTexts {

    private MessageTexts() {}

    public static final String BTN_BOOK = "Book appointment";
    public static final String BTN_CHECK_IN = "I'm at the clinic";
    public static final String BTN_TALK = "Talk to clinic";
    public static final String BTN_ANOTHER_DAY = "Another day";
    public static final String BTN_YES = "YES";
    public static final String BTN_CHANGE = "CHANGE";
    public static final String BTN_CANCEL = "CANCEL";
    public static final String BTN_YES_CANCEL = "Yes, cancel";
    public static final String BTN_KEEP = "Keep it";
    public static final String BTN_CALL_NEXT = "Call next";

    public static String greeting(Clinic c) {
        return "Hi! This is " + c.getName() + ". I can book an appointment for you or check you in if you're at the clinic. What would you like to do?";
    }

    public static String emergency() {
        return "This sounds like it could be an emergency. Please call 108 for an ambulance or go to the nearest emergency room right now. "
                + "Don't wait for a reply here. I've also alerted the clinic team.";
    }

    public static String crisis() {
        return "I'm really sorry you're going through this. You don't have to face it alone. Please call Tele-MANAS on 14416 "
                + "(free, 24x7) or 112 if you're in immediate danger. I've let the clinic team know so someone can reach out.";
    }

    public static String handedOff() {
        return "Thanks. I've passed this to the clinic team and they'll reply here soon.";
    }

    public static String pickDoctor() {
        return "Which doctor would you like to see?";
    }

    public static String pickDay(Doctor d) {
        return "When would you like to see " + d.displayName() + "?";
    }

    public static String noDays(Doctor d) {
        return "Sorry, " + d.displayName() + " has no free slots in the next few days. I've asked the clinic team to help you.";
    }

    public static String offerSlots(Doctor d, String dayLabel, String t1, String t2, String until) {
        String day = dayLabel.equalsIgnoreCase("Today") || dayLabel.equalsIgnoreCase("Tomorrow") ? dayLabel.toLowerCase() : "on " + dayLabel;
        String lead = until == null ? "" : d.displayName() + " is seeing patients till " + until + ". ";
        if (t2 == null) return lead + t1 + " is free " + day + ". Shall I book it?";
        return lead + t1 + " and " + t2 + " are free " + day + ". Which works for you?";
    }

    public static String noSlotsThatDay(Doctor d, String dayLabel) {
        return d.displayName() + " has no free slots " + dayLabel.toLowerCase() + ". Pick another day?";
    }

    public static String booked(Clinic c, Doctor d, LocalDateTime start) {
        return "Booked. " + d.displayName() + " will see you " + Fmt.when(start) + " at " + c.locationLine() + ". "
                + "Reply CHANGE to reschedule or CANCEL to cancel.";
    }

    public static String askReason() {
        return "Anything the doctor should know? Reply with the reason for your visit (for example: fever for 3 days).";
    }

    public static String reasonNoted() {
        return "Noted, thank you. See you soon.";
    }

    public static String slotTaken() {
        return "Sorry, that slot was just taken. Here are the next free ones.";
    }

    public static String rescheduled(Clinic c, Doctor d, LocalDateTime start) {
        return "Done, your appointment is moved. " + d.displayName() + " will see you " + Fmt.when(start) + " at " + c.locationLine() + ".";
    }

    public static String confirmCancel(Doctor d, LocalDateTime start) {
        return "Cancel your appointment with " + d.displayName() + " " + Fmt.when(start) + "?";
    }

    public static String cancelled(Doctor d, LocalDateTime start) {
        return "Your appointment with " + d.displayName() + " " + Fmt.when(start) + " is cancelled. Message us anytime to book again.";
    }

    public static String keptAppointment() {
        return "Okay, your appointment stays as it is.";
    }

    public static String noUpcoming() {
        return "I couldn't find an upcoming appointment for this number. Would you like to book one?";
    }

    public static String reminder(Clinic c, Doctor d, LocalDateTime start) {
        return "Reminder: you have an appointment with " + d.displayName() + " " + Fmt.when(start) + " at " + c.locationLine() + ". "
                + "Reply YES to confirm, CHANGE to reschedule, or CANCEL to cancel.";
    }

    public static String reminderConfirmed() {
        return "Thanks for confirming. See you then.";
    }

    public static String checkedIn(int token, int ahead, int etaMinutes) {
        if (ahead == 0) return "You're checked in. Your token is #" + token + ". You're next, please stay close.";
        return "You're checked in. Your token is #" + token + ". " + ahead + (ahead == 1 ? " patient is" : " patients are")
                + " ahead of you, about " + etaMinutes + " minutes. I'll message you when it's your turn.";
    }

    public static String comeIn(int token, Doctor d) {
        return "Token #" + token + ": it's your turn. Please go in to see " + d.displayName() + " now.";
    }

    public static String almostTurn(int token) {
        return "Token #" + token + ": you're next after the current patient. Please be near the consultation room.";
    }

    public static String delay(Doctor d, int minutes, String note) {
        String base = d.displayName() + " is running about " + minutes + " minutes late today. Sorry for the wait.";
        return note == null || note.isBlank() ? base : base + " " + note.trim();
    }

    public static String noDoctorNow() {
        return "No doctor is seeing patients right now. Would you like to book an appointment instead?";
    }

    public static String followUpDue(Doctor d, String title, String t1, String t2) {
        String who = d == null ? "the doctor" : d.displayName();
        String slots = t2 == null ? t1 : t1 + " or " + t2;
        return "It's time for your " + title + " with " + who + ". I can book you in at " + slots + ". Which works?";
    }

    public static String optedOut() {
        return "Okay, you won't get reminders from us anymore. Message us anytime if you need the clinic.";
    }

    public static String reviewRequest(Clinic c) {
        return "Thanks for visiting " + c.getName() + ". If you have a minute, a Google review helps us a lot: " + c.getGoogleReviewLink();
    }

    public static String clinicCancelled(Doctor d, LocalDateTime start, String reason) {
        String base = "Your appointment with " + d.displayName() + " " + Fmt.when(start) + " has been cancelled by the clinic.";
        return (reason == null || reason.isBlank() ? base : base + " " + reason.trim()) + " Reply here to book another time.";
    }
}
