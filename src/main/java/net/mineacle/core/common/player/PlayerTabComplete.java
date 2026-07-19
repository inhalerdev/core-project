package net.mineacle.core.common.player;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlayerTabComplete {

    private PlayerTabComplete() {
    }

    public static List<String> onlinePlayers(
            Player viewer,
            String input
    ) {
        return onlinePlayers(viewer, input, false);
    }

    public static List<String> onlinePlayers(
            Player viewer,
            String input,
            boolean includeSelf
    ) {
        String partial = input == null
                ? ""
                : input.trim();

        Map<String, String> completions = new LinkedHashMap<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!includeSelf
                    && viewer != null
                    && online.getUniqueId()
                    .equals(viewer.getUniqueId())) {
                continue;
            }

            if (viewer != null && !viewer.canSee(online)) {
                continue;
            }

            if (!partial.isEmpty()
                    && !DisplayNames.startsWithDisplay(
                    online,
                    partial
            )) {
                continue;
            }

            String commandName =
                    DisplayNames.commandDisplayName(online);

            if (commandName == null || commandName.isBlank()) {
                continue;
            }

            completions.putIfAbsent(
                    commandName.toLowerCase(Locale.ROOT),
                    commandName
            );
        }

        List<String> result =
                new ArrayList<>(completions.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    /**
     * Mineacle command UX: show every valid follow-up option immediately,
     * even after part of an option has been entered.
     */
    public static List<String> options(
            String input,
            Iterable<String> options
    ) {
        return uniqueOptions(options, null);
    }

    public static List<String> optionsFiltered(
            String input,
            Iterable<String> options
    ) {
        String partial = input == null
                ? ""
                : input.trim().toLowerCase(Locale.ROOT);

        return uniqueOptions(options, partial);
    }

    private static List<String> uniqueOptions(
            Iterable<String> options,
            String partial
    ) {
        if (options == null) {
            return List.of();
        }

        Map<String, String> unique = new LinkedHashMap<>();

        for (String option : options) {
            if (option == null || option.isBlank()) {
                continue;
            }

            String normalized = option.toLowerCase(Locale.ROOT);

            if (partial != null
                    && !partial.isEmpty()
                    && !normalized.startsWith(partial)) {
                continue;
            }

            unique.putIfAbsent(normalized, option);
        }

        return List.copyOf(unique.values());
    }
}
