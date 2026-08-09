package net.mineacle.core.placeholders;

import net.mineacle.core.Core;
import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.stats.service.StatsStorageService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class PlaceholderSnapshotService {

    private final Core core;
    private final EconomyService economyService;
    private final StatsService statsService;

    private volatile Snapshot snapshot = Snapshot.empty();

    public PlaceholderSnapshotService(
            Core core,
            EconomyService economyService,
            StatsService statsService
    ) {
        this.core = core;
        this.economyService = economyService;
        this.statsService = statsService;
    }

    public void refresh() {
        if (!Bukkit.isPrimaryThread()) {
            core.getServer().getScheduler().runTask(
                    core,
                    this::refresh
            );
            return;
        }

        try {
            int maximum = Math.max(
                    1,
                    Math.min(
                            1_000,
                            core.getConfig().getInt(
                                    "placeholders.cache.maximum-leaderboard-positions",
                                    100
                            )
                    )
            );

            BalanceSnapshot balances =
                    buildBalanceSnapshot(maximum);
            List<StatEntry> kills = buildStats(
                    statsService == null
                            ? List.of()
                            : statsService.topKills(maximum)
            );
            List<StatEntry> deaths = buildStats(
                    statsService == null
                            ? List.of()
                            : statsService.topDeaths(maximum)
            );
            List<StatEntry> playtime = buildStats(
                    statsService == null
                            ? List.of()
                            : statsService.topPlaytime(maximum)
            );

            snapshot = new Snapshot(
                    balances.entries(),
                    balances.ranks(),
                    kills,
                    deaths,
                    playtime
            );
        } catch (Exception exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not refresh Mineacle placeholder snapshots",
                    exception
            );
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private BalanceSnapshot buildBalanceSnapshot(
            int maximum
    ) {
        if (economyService == null) {
            return new BalanceSnapshot(
                    List.of(),
                    Map.of()
            );
        }

        List<Map.Entry<UUID, Long>> all =
                economyService.topBalances(
                        Integer.MAX_VALUE
                );
        List<BalanceEntry> visible =
                new ArrayList<>();
        Map<UUID, Integer> ranks =
                new HashMap<>();

        int rank = 0;

        for (Map.Entry<UUID, Long> entry : all) {
            if (entry == null
                    || entry.getKey() == null
                    || entry.getValue() == null
                    || entry.getValue() <= 0L) {
                continue;
            }

            rank++;
            ranks.put(entry.getKey(), rank);

            if (visible.size() < maximum) {
                visible.add(
                        new BalanceEntry(
                                player(entry.getKey()),
                                entry.getValue()
                        )
                );
            }
        }

        return new BalanceSnapshot(
                List.copyOf(visible),
                Map.copyOf(ranks)
        );
    }

    private List<StatEntry> buildStats(
            List<StatsStorageService.StatProfile> profiles
    ) {
        if (profiles == null || profiles.isEmpty()) {
            return List.of();
        }

        List<StatEntry> entries =
                new ArrayList<>(profiles.size());

        for (StatsStorageService.StatProfile profile
                : profiles) {
            if (profile == null
                    || profile.uuid() == null) {
                continue;
            }

            entries.add(
                    new StatEntry(
                            player(profile.uuid()),
                            profile
                    )
            );
        }

        return List.copyOf(entries);
    }

    private PlayerIdentity player(UUID uuid) {
        OfflinePlayer player =
                Bukkit.getOfflinePlayer(uuid);
        NicknameService nicknameService =
                ChatModule.nicknameService();

        String username;

        if (nicknameService != null) {
            username = nicknameService.username(player);
        } else {
            username = player.getName();
        }

        if (username == null || username.isBlank()) {
            username = uuid.toString();
        }

        String displayName = nicknameService == null
                ? username
                : nicknameService.displayName(player);
        String nickname = nicknameService == null
                ? ""
                : nicknameService.nickname(player);

        return new PlayerIdentity(
                uuid,
                username,
                displayName == null || displayName.isBlank()
                        ? username
                        : displayName,
                nickname == null ? "" : nickname
        );
    }

    public record Snapshot(
            List<BalanceEntry> balances,
            Map<UUID, Integer> balanceRanks,
            List<StatEntry> kills,
            List<StatEntry> deaths,
            List<StatEntry> playtime
    ) {

        private static Snapshot empty() {
            return new Snapshot(
                    List.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        public BalanceEntry balanceAt(int position) {
            if (position < 1
                    || position > balances.size()) {
                return null;
            }

            return balances.get(position - 1);
        }

        public int balanceRank(UUID uuid) {
            if (uuid == null) {
                return 0;
            }

            return balanceRanks.getOrDefault(uuid, 0);
        }

        public StatEntry statAt(
                String type,
                int position
        ) {
            List<StatEntry> source = switch (type) {
                case "kills" -> kills;
                case "deaths" -> deaths;
                case "playtime" -> playtime;
                default -> List.of();
            };

            if (position < 1
                    || position > source.size()) {
                return null;
            }

            return source.get(position - 1);
        }
    }

    public record PlayerIdentity(
            UUID uuid,
            String username,
            String displayName,
            String nickname
    ) {
    }

    public record BalanceEntry(
            PlayerIdentity player,
            long cents
    ) {
    }

    public record StatEntry(
            PlayerIdentity player,
            StatsStorageService.StatProfile profile
    ) {
    }

    private record BalanceSnapshot(
            List<BalanceEntry> entries,
            Map<UUID, Integer> ranks
    ) {
    }
}
