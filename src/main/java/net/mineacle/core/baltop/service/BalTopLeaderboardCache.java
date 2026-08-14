package net.mineacle.core.baltop.service;

import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class BalTopLeaderboardCache {

    private static final long CACHE_TTL_NANOS =
            TimeUnit.SECONDS.toNanos(5L);

    private final EconomyService economyService;
    private Snapshot snapshot = Snapshot.empty();

    public BalTopLeaderboardCache(
            EconomyService economyService
    ) {
        this.economyService = economyService;
    }

    public synchronized Snapshot current() {
        long now = System.nanoTime();

        if (snapshot.validAt(now)) {
            return snapshot;
        }

        snapshot = rebuild(now);
        return snapshot;
    }

    public synchronized Snapshot refresh() {
        snapshot = rebuild(System.nanoTime());
        return snapshot;
    }

    public synchronized void clear() {
        snapshot = Snapshot.empty();
    }

    private Snapshot rebuild(long builtAtNanos) {
        List<Map.Entry<UUID, Long>> balances =
                economyService.topBalances(Integer.MAX_VALUE);
        List<Entry> entries =
                new ArrayList<>(balances.size());
        Map<UUID, Entry> byPlayer =
                new LinkedHashMap<>();

        for (int index = 0;
             index < balances.size();
             index++) {
            Map.Entry<UUID, Long> balance =
                    balances.get(index);
            UUID playerId =
                    balance.getKey();
            OfflinePlayer player =
                    Bukkit.getOfflinePlayer(playerId);
            String displayName =
                    DisplayNames.displayName(player);
            Entry entry =
                    new Entry(
                            playerId,
                            balance.getValue(),
                            index + 1,
                            displayName,
                            normalize(displayName)
                    );

            entries.add(entry);
            byPlayer.put(playerId, entry);
        }

        return new Snapshot(
                builtAtNanos,
                List.copyOf(entries),
                Map.copyOf(byPlayer)
        );
    }

    public record Entry(
            UUID playerId,
            long balanceCents,
            int placement,
            String displayName,
            String normalizedDisplayName
    ) {
    }

    public static final class Snapshot {

        private final long builtAtNanos;
        private final List<Entry> entries;
        private final Map<UUID, Entry> byPlayer;

        private Snapshot(
                long builtAtNanos,
                List<Entry> entries,
                Map<UUID, Entry> byPlayer
        ) {
            this.builtAtNanos = builtAtNanos;
            this.entries = entries;
            this.byPlayer = byPlayer;
        }

        private static Snapshot empty() {
            return new Snapshot(
                    Long.MIN_VALUE,
                    List.of(),
                    Map.of()
            );
        }

        public List<Entry> entries() {
            return entries;
        }

        public Entry player(UUID playerId) {
            if (playerId == null) {
                return null;
            }

            return byPlayer.get(playerId);
        }

        public List<Entry> search(String rawQuery) {
            String query = normalize(rawQuery);

            if (query.isBlank()) {
                return entries;
            }

            List<Entry> matches =
                    new ArrayList<>();

            for (Entry entry : entries) {
                if (entry.normalizedDisplayName()
                        .contains(query)) {
                    matches.add(entry);
                }
            }

            return List.copyOf(matches);
        }

        public String exactPublicName(String rawQuery) {
            String query = normalize(rawQuery);
            String match = null;

            if (query.isBlank()) {
                return "";
            }

            for (Entry entry : entries) {
                if (!entry.normalizedDisplayName()
                        .equals(query)) {
                    continue;
                }

                if (match != null) {
                    return "";
                }

                match = entry.displayName();
            }

            return match == null ? "" : match;
        }

        private boolean validAt(long now) {
            return builtAtNanos != Long.MIN_VALUE
                    && now - builtAtNanos < CACHE_TTL_NANOS;
        }
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }

        String clean = TextColor.strip(input)
                .trim();

        if (clean.startsWith(".")) {
            clean = clean.substring(1);
        }

        return clean.toLowerCase(Locale.ROOT);
    }
}
