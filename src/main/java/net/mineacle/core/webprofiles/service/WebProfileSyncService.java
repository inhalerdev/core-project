package net.mineacle.core.webprofiles.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.StatsModule;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import net.mineacle.core.webprofiles.model.WebProfileRecord;
import net.mineacle.core.webprofiles.storage.WebProfileRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class WebProfileSyncService {

    private final Core core;
    private final FileConfiguration config;
    private final WebProfileRepository repository;
    private BukkitTask syncTask;

    public WebProfileSyncService(Core core, FileConfiguration config, WebProfileRepository repository) {
        this.core = core;
        this.config = config;
        this.repository = repository;
    }

    public void start() {
        if (!config.getBoolean("enabled", true)) {
            core.getLogger().info("Web profiles are disabled");
            return;
        }

        repository.initialize();

        long intervalTicks = Math.max(20L, config.getLong("sync.interval-seconds", 120L) * 20L);

        syncTask = core.getServer().getScheduler().runTaskTimer(core, this::syncAll, 80L, intervalTicks);
        core.getLogger().info("Web profile sync enabled");
    }

    public void stop() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }

        if (config.getBoolean("sync.mark-offline-on-disable", true)) {
            core.getServer().getScheduler().runTaskAsynchronously(core, repository::markOffline);
        }
    }

    public void syncAll() {
        StatsService stats = StatsModule.statsService();
        EconomyService economy = EconomyModule.economyService();

        if (stats == null || economy == null) {
            return;
        }

        LinkedHashSet<UUID> ids = new LinkedHashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            ids.add(player.getUniqueId());
        }

        if (config.getBoolean("sync.include-known-offline-players", true)) {
            int limit = Math.max(1, config.getInt("sync.offline-player-pull-limit", 10000));
            int count = 0;

            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                ids.add(player.getUniqueId());
                count++;

                if (count >= limit) {
                    break;
                }
            }
        }

        int leaderboardPull = Math.max(100, config.getInt("sync.leaderboard-pull-limit", 500));

        for (Map.Entry<UUID, Long> entry : economy.topBalances(leaderboardPull)) {
            ids.add(entry.getKey());
        }

        stats.topPlaytime(leaderboardPull).forEach(profile -> ids.add(profile.uuid()));
        stats.topKills(leaderboardPull).forEach(profile -> ids.add(profile.uuid()));
        stats.topDeaths(leaderboardPull).forEach(profile -> ids.add(profile.uuid()));

        List<WebProfileRecord> records = new ArrayList<>();

        for (UUID id : ids) {
            WebProfileRecord record = record(id, stats, economy, Bukkit.getPlayer(id) != null);

            if (record != null) {
                records.add(record);
            }
        }

        if (records.isEmpty()) {
            return;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> repository.upsertAll(records));
    }

    public void syncPlayer(Player player, boolean online) {
        StatsService stats = StatsModule.statsService();
        EconomyService economy = EconomyModule.economyService();

        if (stats == null || economy == null) {
            return;
        }

        boolean actuallyOnline = online && player.isOnline();
        WebProfileRecord record = record(player.getUniqueId(), stats, economy, actuallyOnline);

        if (record == null) {
            return;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> repository.upsertAll(List.of(record)));
    }

    private WebProfileRecord record(UUID uuid, StatsService stats, EconomyService economy, boolean online) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String username = offline.getName();

        if (username == null || username.isBlank()) {
            return null;
        }

        Player player = Bukkit.getPlayer(uuid);
        long now = System.currentTimeMillis();

        long balance = economy.getBalanceCents(uuid);
        long kills = stats.kills(uuid);
        long deaths = stats.deaths(uuid);
        long playtime = stats.playtimeSeconds(uuid);

        int moneyRank = balance <= 0L ? 0 : moneyRank(uuid, economy);
        int killsRank = kills <= 0L ? 0 : stats.rankKills(uuid);
        int playtimeRank = playtime <= 0L ? 0 : stats.rankPlaytime(uuid);

        String displayName = player != null ? DisplayNames.displayName(player) : username;
        Rank rank = player != null ? rank(player) : new Rank(config.getString("rank.default-name", "Member"), 0);

        TeamData team = teamData(uuid);
        long firstJoinedAt = offline.getFirstPlayed() <= 0L ? now : offline.getFirstPlayed();
        long lastSeen = online ? now : offline.getLastSeen();

        if (lastSeen <= 0L) {
            lastSeen = now;
        }

        return new WebProfileRecord(
                uuid,
                username,
                displayName,
                rank.name(),
                rank.weight(),
                team.id(),
                team.name(),
                team.role(),
                team.joinedAt(),
                balance,
                economy.format(balance),
                playtime,
                stats.playtime(uuid),
                kills,
                deaths,
                deaths <= 0L ? kills : Math.round((kills / (double) deaths) * 100.0D) / 100.0D,
                moneyRank,
                killsRank,
                playtimeRank,
                firstJoinedAt,
                lastSeen,
                online,
                now
        );
    }

    private TeamData teamData(UUID uuid) {
        TeamService teamService = TeamsModule.teamService();

        if (teamService == null) {
            return TeamData.none();
        }

        TeamRecord team = teamService.getTeamByPlayer(uuid);
        TeamMemberRecord member = teamService.getMember(uuid);

        if (team == null || member == null) {
            return TeamData.none();
        }

        return new TeamData(
                team.teamId(),
                team.name(),
                roleLabel(member.role().name()),
                member.joinedAt()
        );
    }

    private String roleLabel(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return "";
        }

        String normalized = roleName.toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "MVP" -> "MVP";
            case "VIP" -> "VIP";
            case "FOUNDER" -> "Founder";
            case "ADMIN" -> "Admin";
            case "MEMBER" -> "Member";
            default -> normalized.charAt(0) + normalized.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    private int moneyRank(UUID uuid, EconomyService economy) {
        List<Map.Entry<UUID, Long>> entries = economy.topBalances(Integer.MAX_VALUE)
                .stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0L)
                .toList();

        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getKey().equals(uuid)) {
                return i + 1;
            }
        }

        return 0;
    }

    private Rank rank(Player player) {
        ConfigurationSection ranks = config.getConfigurationSection("rank.permission-ranks");

        if (ranks == null) {
            return new Rank(config.getString("rank.default-name", "Member"), 0);
        }

        Rank best = new Rank(config.getString("rank.default-name", "Member"), 0);

        for (String key : ranks.getKeys(false)) {
            ConfigurationSection section = ranks.getConfigurationSection(key);

            if (section == null) {
                continue;
            }

            String permission = section.getString("permission", "");

            if (permission.isBlank() || !player.hasPermission(permission)) {
                continue;
            }

            int weight = section.getInt("weight", 0);

            if (weight > best.weight()) {
                best = new Rank(section.getString("name", key), weight);
            }
        }

        return best;
    }

    private record Rank(String name, int weight) {
    }

    private record TeamData(String id, String name, String role, long joinedAt) {
        private static TeamData none() {
            return new TeamData("", "", "", 0L);
        }
    }
}
