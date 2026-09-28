package com.siletry.common;

/**
 * Detects the script a patient writes in. Good enough for routing and stats.
 */
public final class Language {

    public static final String ENGLISH = "en";
    public static final String KANNADA = "kn";
    public static final String HINDI = "hi";
    public static final String TAMIL = "ta";
    public static final String TELUGU = "te";
    public static final String MALAYALAM = "ml";

    private Language() {}

    public static String detect(String text) {
        if (text == null || text.isBlank()) return null;
        int kn = 0, hi = 0, ta = 0, te = 0, ml = 0, latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0C80 && c <= 0x0CFF) kn++;
            else if (c >= 0x0900 && c <= 0x097F) hi++;
            else if (c >= 0x0B80 && c <= 0x0BFF) ta++;
            else if (c >= 0x0C00 && c <= 0x0C7F) te++;
            else if (c >= 0x0D00 && c <= 0x0D7F) ml++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
        }
        int max = Math.max(Math.max(Math.max(kn, hi), Math.max(ta, te)), Math.max(ml, latin));
        if (max == 0) return null;
        if (max == kn) return KANNADA;
        if (max == hi) return HINDI;
        if (max == ta) return TAMIL;
        if (max == te) return TELUGU;
        if (max == ml) return MALAYALAM;
        return ENGLISH;
    }
}
