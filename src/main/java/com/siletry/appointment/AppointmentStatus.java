package com.siletry.appointment;

import java.util.EnumSet;
import java.util.Set;

public enum AppointmentStatus {
    CONFIRMED,
    /** Checked in, sitting in the lobby. */
    WAITING,
    /** Called in, with the doctor now. */
    IN_CONSULT,
    SEEN,
    NO_SHOW,
    CANCELLED;

    /** Statuses that occupy a slot. */
    public static final Set<AppointmentStatus> ACTIVE = EnumSet.of(CONFIRMED, WAITING, IN_CONSULT, SEEN);

    public boolean occupiesSlot() {
        return ACTIVE.contains(this);
    }
}
