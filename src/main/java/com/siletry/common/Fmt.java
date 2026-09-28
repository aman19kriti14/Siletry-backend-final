package com.siletry.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human formats used in WhatsApp messages: "4:30 pm", "Wed 23 Sep". */
public final class Fmt {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private Fmt() {}

    public static String time(LocalTime t) {
        return TIME.format(t).toLowerCase(Locale.ENGLISH);
    }

    public static String time(LocalDateTime t) {
        return time(t.toLocalTime());
    }

    public static String day(LocalDate d) {
        return DAY.format(d);
    }

    /** "Today", "Tomorrow" or "Thu 24 Sep". */
    public static String relativeDay(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.plusDays(1))) return "Tomorrow";
        return day(d);
    }

    /** "Wed 23 Sep at 5:30 pm" or "today at 5:30 pm" */
    public static String when(LocalDateTime t) {
        String rel = relativeDay(t.toLocalDate());
        String dayPart = rel.equals("Today") || rel.equals("Tomorrow") ? rel.toLowerCase(Locale.ENGLISH) : rel;
        return dayPart + " at " + time(t);
    }
}
