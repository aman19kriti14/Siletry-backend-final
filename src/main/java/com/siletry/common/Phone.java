package com.siletry.common;

/**
 * Phone numbers are stored as digits with country code, no plus sign: 919845012345.
 */
public final class Phone {

    private Phone() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        if (digits.length() == 10) return "91" + digits;
        if (digits.length() == 11 && digits.startsWith("0")) return "91" + digits.substring(1);
        return digits;
    }

    /** 919845012345 -> +91 98450 12345 */
    public static String pretty(String normalized) {
        if (normalized == null) return null;
        if (normalized.length() == 12 && normalized.startsWith("91")) {
            String n = normalized.substring(2);
            return "+91 " + n.substring(0, 5) + " " + n.substring(5);
        }
        return "+" + normalized;
    }
}
