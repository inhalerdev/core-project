package net.mineacle.core.common.text;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextColor {

    private static final Pattern HEX_PATTERN = Pattern.compile(
            "(?i)&?#([0-9a-f]{6})"
    );

    private static final Pattern LEGACY_PATTERN = Pattern.compile(
            "(?i)(?:&|§)[0-9a-fk-orx]"
    );

    private TextColor() {
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer output = new StringBuffer(input.length() + 16);

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");

            for (char character : hex.toCharArray()) {
                replacement.append('§').append(character);
            }

            matcher.appendReplacement(
                    output,
                    Matcher.quoteReplacement(replacement.toString())
            );
        }

        matcher.appendTail(output);

        return ChatColor.translateAlternateColorCodes(
                '&',
                output.toString()
        );
    }

    public static String strip(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String stripped = ChatColor.stripColor(color(input));
        return stripped == null ? "" : stripped;
    }

    public static boolean containsFormatting(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }

        return HEX_PATTERN.matcher(input).find()
                || LEGACY_PATTERN.matcher(input).find();
    }
}
