package com.siletry.messaging;

public enum ConversationState {
    /** Siletry is handling it. */
    BOT,
    /** Waiting for staff. Shows in "Needs you". */
    NEEDS_YOU,
    /** Staff took over and replied. */
    YOU_REPLIED,
    /** Done. */
    HANDLED
}
