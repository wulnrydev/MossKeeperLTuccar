package dev.wulnry.mosskeeper.util;

import java.util.Locale;

/**
 * Turkce karakterli komut/urun isimlerini guvenli bir sekilde normalize eder.
 * <p>
 * Oyuncu "takasKarşılığı", "takaskarsiligi" veya "TAKASKARŞILIĞI" yazsa da
 * ayni alt komuta ulasir. Ayni sekilde urun isimleri de buyuk/kucuk harf ve
 * Turkce karakter farki gozetmeden eslesir.
 */
public final class TextUtil {

    private static final Locale TR = new Locale("tr", "TR");

    private TextUtil() {
    }

    /**
     * Turkce harfleri ASCII karsiliklarina indirger ve kucuk harfe cevirir.
     */
    public static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String lower = input.toLowerCase(TR);
        StringBuilder builder = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case 'ç' -> builder.append('c');
                case 'ğ' -> builder.append('g');
                case 'ı' -> builder.append('i');
                case 'ö' -> builder.append('o');
                case 'ş' -> builder.append('s');
                case 'ü' -> builder.append('u');
                case 'â' -> builder.append('a');
                case 'î' -> builder.append('i');
                case 'û' -> builder.append('u');
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * Urun ismi olarak kabul edilebilir mi? (harf, rakam, alt cizgi, tire)
     */
    public static boolean isValidName(String input) {
        if (input == null || input.isEmpty() || input.length() > 32) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    /**
     * "DIAMOND_SWORD" -> "Diamond Sword"
     */
    public static String prettify(String enumName) {
        if (enumName == null || enumName.isEmpty()) {
            return "";
        }
        String[] parts = enumName.toLowerCase(Locale.ENGLISH).split("_");
        StringBuilder builder = new StringBuilder(enumName.length());
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part, 1, part.length());
        }
        return builder.toString();
    }

    /**
     * Sayiya cevirir.
     * <p>
     * Sentinel (-1 gibi) yerine {@code null} doner; boylece yoneticinin
     * bilerek yazdigi negatif deger "gecersiz sayi" ile karistirilmaz.
     *
     * @return sayi degilse {@code null}
     */
    public static Integer parseInt(String input) {
        if (input == null) {
            return null;
        }
        try {
            return Integer.valueOf(input.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
