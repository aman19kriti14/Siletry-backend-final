package com.siletry.messaging;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Catches messages that must never wait for staff. When this fires, the patient gets an
 * immediate "call 108" reply and the doctor is alerted. Errs on the side of flagging.
 */
public final class EmergencyDetector {

    public enum Kind { MEDICAL, CRISIS }

    private EmergencyDetector() {}

    private static final List<Pattern> MEDICAL = List.of(
            p("chest\\s*pain"), p("pain\\s+in\\s+(my\\s+)?chest"), p("heart\\s*attack"),
            p("can'?t\\s+breathe"), p("cannot\\s+breathe"), p("not\\s+breathing"), p("difficulty\\s+breathing"),
            p("trouble\\s+breathing"), p("breathless(ness)?"), p("short(ness)?\\s+of\\s+breath"),
            p("unconscious"), p("fainted"), p("passed\\s+out"), p("not\\s+responding"), p("collapsed"),
            p("heavy\\s+bleeding"), p("bleeding\\s+(a\\s+lot|heavily|badly|won'?t\\s+stop)"),
            p("seizure"), p("convulsion"), p("fits"), p("stroke"), p("paralys(is|ed)"), p("face\\s+drooping"),
            p("slurred\\s+speech"), p("poison(ed|ing)?"), p("overdose"), p("snake\\s*bite"), p("choking"),
            p("severe\\s+burn"), p("accident"), p("head\\s+injury"), p("very\\s+high\\s+fever.*(child|baby)"));

    private static final List<Pattern> CRISIS = List.of(
            p("suicid(e|al)"), p("kill\\s+myself"), p("end\\s+my\\s+life"), p("want\\s+to\\s+die"),
            p("self[\\s-]?harm"), p("hurt\\s+myself"));

    /** Indic-script phrases: plain substring match. */
    private static final List<String> MEDICAL_INDIC = List.of(
            "ಎದೆ ನೋವು", "ಉಸಿರಾಟದ ತೊಂದರೆ", "ಪ್ರಜ್ಞೆ ತಪ್ಪಿ",
            "सीने में दर्द", "छाती में दर्द", "सांस नहीं", "साँस नहीं", "बेहोश", "दिल का दौरा");

    public static Optional<Kind> detect(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String lower = text.toLowerCase(Locale.ROOT);
        for (Pattern pt : CRISIS) if (pt.matcher(lower).find()) return Optional.of(Kind.CRISIS);
        for (Pattern pt : MEDICAL) if (pt.matcher(lower).find()) return Optional.of(Kind.MEDICAL);
        for (String s : MEDICAL_INDIC) if (text.contains(s)) return Optional.of(Kind.MEDICAL);
        return Optional.empty();
    }

    private static Pattern p(String regex) {
        return Pattern.compile("\\b" + regex + "\\b");
    }
}
