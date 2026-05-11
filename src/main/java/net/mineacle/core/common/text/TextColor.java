package net.mineacle.core.common.text;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextColor {

    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)&?#([A-Fa-f0-9]{6})");

    private TextColor() {
    }

    public static String color(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String output = input.replace("&7", "&#bbbbbb");

        Matcher matcher = HEX_PATTERN.matcher(output);
        StringBuilder builder = new StringBuilder();

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");

            for (char character : hex.toCharArray()) {
                replacement.append('§').append(character);
            }

            matcher.appendReplacement(builder, replacement.toString());
        }

        matcher.appendTail(builder);

        return ChatColor.translateAlternateColorCodes('&', builder.toString());
    }

    public static String strip(String input) {
        return ChatColor.stripColor(color(input));
    }
}