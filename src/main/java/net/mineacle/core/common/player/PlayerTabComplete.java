package net.mineacle.core.common.player;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PlayerTabComplete {

    private PlayerTabComplete() {
    }

    public static List<String> onlinePlayers(Player viewer, String partial) {
        return onlinePlayers(viewer, partial, false);
    }

    public static List<String> onlinePlayers(Player viewer, String partial, boolean includeSelf) {
        String normalizedPartial = normalize(partial);
        Set<String> completions = new LinkedHashSet<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!includeSelf && viewer != null && online.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }

            String username = DisplayNames.username(online);
            String displayName = DisplayNames.displayName(online);
            String nickname = DisplayNames.nickname(online);
            String commandName = DisplayNames.commandDisplayName(online);

            if (matches(username, displayName, nickname, normalizedPartial)) {
                completions.add(commandName == null || commandName.isBlank() ? username : commandName);
            }
        }

        return new ArrayList<>(completions);
    }

    public static boolean matches(String username, String displayName, String nickname, String normalizedPartial) {
        if (normalizedPartial == null || normalizedPartial.isBlank()) {
            return true;
        }

        return normalize(username).startsWith(normalizedPartial)
                || normalize(displayName).startsWith(normalizedPartial)
                || normalize(nickname).startsWith(normalizedPartial);
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }

        String cleaned = input.trim();
        if (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }

        return cleaned.toLowerCase(Locale.ROOT);
    }
}
