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

    public static List<String> onlinePlayers(Player viewer, String input) {
        String partial = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        Set<String> completions = new LinkedHashSet<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (viewer != null && online.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }

            String commandName = DisplayNames.commandDisplayName(online);
            String displayName = DisplayNames.displayName(online);
            String username = online.getName();

            if (partial.isEmpty() || commandName.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(commandName);
            }

            if (partial.isEmpty() || displayName.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(commandName);
            }

            if (partial.isEmpty() || username.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(username);
            }
        }

        return new ArrayList<>(completions);
    }

    public static List<String> options(String input, Iterable<String> options) {
        String partial = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();

        for (String option : options) {
            if (option == null || option.isBlank()) {
                continue;
            }

            if (partial.isEmpty() || option.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(option);
            }
        }

        return completions;
    }
}
