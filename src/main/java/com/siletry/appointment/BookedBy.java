package com.siletry.appointment;

public enum BookedBy {
    /** Booked by the bot on WhatsApp, including QR scans. */
    SILETRY,
    /** Booked by staff from the web app. */
    FRONT_DESK,
    /** Walked in without a booking. */
    WALK_IN
}
