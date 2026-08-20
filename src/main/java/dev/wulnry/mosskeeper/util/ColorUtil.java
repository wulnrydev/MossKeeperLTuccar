package dev.wulnry.mosskeeper.util;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hem eski ({@code &a}) hem de HEX ({@code &#RRGGBB}) renk kodlarini destekleyen
 * cevirici. Regex tek sefer derlenir; her cagrida yeni Pattern olusturulmaz.
 */
public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private ColorUtil() {
    }

    /**
     * Metindeki tum renk kodlarini oyun ici formata cevirir.
     */
    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String result = input;
        if (result.indexOf('#') != -1) {
            Matcher matcher = HEX_PATTERN.matcher(result);
            StringBuilder builder = new StringBuilder(result.length() + 32);
            while (matcher.find()) {
                matcher.appendReplacement(builder, Matcher.quoteReplacement(toHexSequence(matcher.group(1))));
            }
            matcher.appendTail(builder);
            result = builder.toString();
        }
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    /**
     * "1A2B3C" -> §x§1§a§2§b§3§c
     * <p>
     * Bungee {@code ChatColor.of(...)} yerine dogrudan sequence uretiyoruz;
     * boylece Bungee-chat bulunmayan ozel sunucu yazilimlarinda da calisir.
     */
    private static String toHexSequence(String hex) {
        StringBuilder builder = new StringBuilder(14);
        builder.append(ChatColor.COLOR_CHAR).append('x');
        for (int i = 0; i < 6; i++) {
            builder.append(ChatColor.COLOR_CHAR).append(Character.toLowerCase(hex.charAt(i)));
        }
        return builder.toString();
    }
}
