package net.mineacle.core.common.player;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

public final class PlayerTabComplete {

    private static final long SNAPSHOT_TTL_NANOS =
            1_000_000_000L;

    private static volatile Snapshot snapshot =
            Snapshot.empty();

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
        String partial =
                DisplayNames.normalizePublicName(
                        input == null
                                ? ""
                                : input.trim()
                );
        Snapshot current = currentSnapshot();
        Iterable<Entry> candidates =
                current.candidates(partial);
        Map<String, String> completions =
                new LinkedHashMap<>();

        for (Entry entry : candidates) {
            Player online =
                    Bukkit.getPlayer(
                            entry.playerId()
                    );

            if (online == null
                    || !online.isOnline()) {
                continue;
            }

            if (!includeSelf
                    && viewer != null
                    && online.getUniqueId()
                    .equals(viewer.getUniqueId())) {
                continue;
            }

            if (viewer != null
                    && !viewer.canSee(online)) {
                continue;
            }

            completions.putIfAbsent(
                    entry.normalizedName(),
                    entry.commandName()
            );
        }

        List<String> result =
                new ArrayList<>(
                        completions.values()
                );
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
        List<String> values =
                uniqueOptions(options, null);
        String partial = input == null
                ? ""
                : input.trim()
                .toLowerCase(Locale.ROOT);

        if (partial.isEmpty()
                || values.size() < 2) {
            return values;
        }

        List<String> preferred =
                new ArrayList<>(values.size());
        List<String> remaining =
                new ArrayList<>(values.size());

        for (String option : values) {
            if (option.toLowerCase(Locale.ROOT)
                    .startsWith(partial)) {
                preferred.add(option);
            } else {
                remaining.add(option);
            }
        }

        preferred.addAll(remaining);
        return List.copyOf(preferred);
    }

    public static List<String> optionsFiltered(
            String input,
            Iterable<String> options
    ) {
        String partial = input == null
                ? ""
                : input.trim()
                .toLowerCase(Locale.ROOT);

        return uniqueOptions(options, partial);
    }

    private static Snapshot currentSnapshot() {
        long now = System.nanoTime();
        Snapshot current = snapshot;

        if (current.validAt(now)) {
            return current;
        }

        synchronized (PlayerTabComplete.class) {
            current = snapshot;

            if (current.validAt(now)) {
                return current;
            }

            Snapshot rebuilt = rebuild(now);
            snapshot = rebuilt;
            return rebuilt;
        }
    }

    private static Snapshot rebuild(long builtAtNanos) {
        List<Entry> all =
                new ArrayList<>();
        NavigableMap<String, List<Entry>> byPrefix =
                new TreeMap<>();

        for (Player online
                : Bukkit.getOnlinePlayers()) {
            String commandName =
                    DisplayNames.commandDisplayName(
                            online
                    );

            if (commandName == null
                    || commandName.isBlank()) {
                continue;
            }

            String normalized =
                    DisplayNames.normalizePublicName(
                            commandName
                    );

            if (normalized.isBlank()) {
                continue;
            }

            Entry entry =
                    new Entry(
                            online.getUniqueId(),
                            commandName,
                            normalized
                    );
            all.add(entry);
            byPrefix.computeIfAbsent(
                    normalized,
                    ignored -> new ArrayList<>()
            ).add(entry);
        }

        List<Entry> immutableAll =
                List.copyOf(all);
        NavigableMap<String, List<Entry>> immutableIndex =
                new TreeMap<>();

        for (Map.Entry<String, List<Entry>> entry
                : byPrefix.entrySet()) {
            immutableIndex.put(
                    entry.getKey(),
                    List.copyOf(entry.getValue())
            );
        }

        return new Snapshot(
                builtAtNanos,
                immutableAll,
                immutableIndex
        );
    }

    private static List<String> uniqueOptions(
            Iterable<String> options,
            String partial
    ) {
        if (options == null) {
            return List.of();
        }

        Map<String, String> unique =
                new LinkedHashMap<>();

        for (String option : options) {
            if (option == null
                    || option.isBlank()) {
                continue;
            }

            String normalized =
                    option.toLowerCase(Locale.ROOT);

            if (partial != null
                    && !partial.isEmpty()
                    && !normalized.startsWith(partial)) {
                continue;
            }

            unique.putIfAbsent(normalized, option);
        }

        return List.copyOf(unique.values());
    }

    private record Entry(
            UUID playerId,
            String commandName,
            String normalizedName
    ) {
    }

    private record Snapshot(
            long builtAtNanos,
            List<Entry> all,
            NavigableMap<String, List<Entry>> byName
    ) {
        private static Snapshot empty() {
            return new Snapshot(
                    Long.MIN_VALUE,
                    List.of(),
                    new TreeMap<>()
            );
        }

        private boolean validAt(long now) {
            return builtAtNanos != Long.MIN_VALUE
                    && now - builtAtNanos
                    < SNAPSHOT_TTL_NANOS;
        }

        private Iterable<Entry> candidates(
                String partial
        ) {
            if (partial == null
                    || partial.isBlank()) {
                return all;
            }

            String upperBound = partial + '\uffff';
            List<Entry> matches =
                    new ArrayList<>();

            for (List<Entry> entries
                    : byName.subMap(
                    partial,
                    true,
                    upperBound,
                    true
            ).values()) {
                matches.addAll(entries);
            }

            return matches;
        }
    }
}
