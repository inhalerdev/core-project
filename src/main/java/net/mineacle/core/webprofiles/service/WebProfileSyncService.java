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
import org.bukkit.World;
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

                if (++count >= limit) {
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
            Player player = Bukkit.getPlayer(id);
            WebProfileRecord record = record(id, player, stats, economy, player != null);

            if (record != null) {
                records.add(record);
            }
        }

        if (!records.isEmpty()) {
            core.getServer().getScheduler().runTaskAsynchronously(core, () -> repository.upsertAll(records));
        }
    }

    public void syncPlayer(Player player, boolean online) {
        StatsService stats = StatsModule.statsService();
        EconomyService economy = EconomyModule.economyService();

        if (stats == null || economy == null) {
            return;
        }

        WebProfileRecord record = record(
                player.getUniqueId(),
                player,
                stats,
                economy,
                online && player.isOnline()
        );

        if (record != null) {
            core.getServer().getScheduler().runTaskAsynchronously(
                    core,
                    () -> repository.upsertAll(List.of(record))
            );
        }
    }

    private WebProfileRecord record(
            UUID uuid,
            Player player,
            StatsService stats,
            EconomyService economy,
            boolean online
    ) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String username = offline.getName();

        if (username == null || username.isBlank()) {
            return null;
        }

        long now = System.currentTimeMillis();
        long balance = economy.getBalanceCents(uuid);
        long kills = stats.kills(uuid);
        long deaths = stats.deaths(uuid);
        long playtime = stats.playtimeSeconds(uuid);

        int moneyRank = balance <= 0L ? 0 : moneyRank(uuid, economy);
        int killsRank = kills <= 0L ? 0 : stats.rankKills(uuid);
        int playtimeRank = playtime <= 0L ? 0 : stats.rankPlaytime(uuid);

        String displayName = player != null ? DisplayNames.displayName(player) : username;
        Rank rank = rank(uuid, player);
        WorldData world = player != null ? worldData(player.getWorld()) : WorldData.none();
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
                rank.key(),
                rank.name(),
                rank.prefix(),
                rank.color(),
                rank.weight(),
                world.key(),
                world.name(),
                world.group(),
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

    private WorldData worldData(World world) {
        if (world == null) {
            return WorldData.none();
        }

        String key = world.getName();
        String path = "worlds.mappings." + key;
        String name = config.getString(path + ".name");
        String group = config.getString(path + ".group");

        if (name != null && !name.isBlank()) {
            return new WorldData(key, name, group == null ? "" : group);
        }

        return switch (key.toLowerCase(Locale.ROOT)) {
            case "spawn1" -> new WorldData(key, "Spawn 1", "spawn");
            case "spawn2" -> new WorldData(key, "Spawn 2", "spawn");
            case "spawn3" -> new WorldData(key, "Spawn 3", "spawn");
            case "origins" -> new WorldData(key, "Overworld", "survival");
            case "world_nether", "origins_nether" -> new WorldData(key, "Nether", "survival");
            case "world_the_end", "origins_the_end" -> new WorldData(key, "End", "survival");
            default -> new WorldData(
                    key,
                    config.getString("worlds.default-name", key),
                    config.getString("worlds.default-group", "other")
            );
        };
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

    private Rank rank(UUID uuid, Player player) {
        if (player != null) {
            return rank(player);
        }

        return repository.findRank(uuid)
                .map(this::storedRank)
                .orElseGet(this::defaultRank);
    }

    private Rank rank(Player player) {
        ConfigurationSection ranks = config.getConfigurationSection("rank.permission-ranks");

        if (ranks == null) {
            return defaultRank();
        }

        Rank best = defaultRank();

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
                best = new Rank(
                        key.toLowerCase(Locale.ROOT),
                        section.getString("name", key),
                        section.getString("prefix", ""),
                        normalizeHex(section.getString("color", "#bbbbbb")),
                        weight
                );
            }
        }

        return best;
    }

    private Rank storedRank(WebProfileRepository.StoredRank stored) {
        Rank fallback = defaultRank();
        String key = stored.key() == null || stored.key().isBlank() ? fallback.key() : stored.key();
        String name = stored.name() == null || stored.name().isBlank() ? fallback.name() : stored.name();
        String prefix = stored.prefix() == null ? fallback.prefix() : stored.prefix();
        String color = stored.color() == null || stored.color().isBlank()
                ? fallback.color()
                : normalizeHex(stored.color());

        return new Rank(key, name, prefix, color, stored.weight());
    }

    private Rank defaultRank() {
        return new Rank(
                config.getString("rank.default-key", "default"),
                config.getString("rank.default-name", "Member"),
                config.getString("rank.default-prefix", ""),
                normalizeHex(config.getString("rank.default-color", "#bbbbbb")),
                0
        );
    }

    private String normalizeHex(String color) {
        if (color == null || color.isBlank()) {
            return "#bbbbbb";
        }

        String value = color.trim();

        if (value.matches("(?i)^#[0-9a-f]{6}$")) {
            return value.toLowerCase(Locale.ROOT);
        }

        return switch (value.toLowerCase(Locale.ROOT)) {
            case "&0" -> "#000000";
            case "&1" -> "#0000aa";
            case "&2" -> "#00aa00";
            case "&3" -> "#00aaaa";
            case "&4" -> "#aa0000";
            case "&5" -> "#aa00aa";
            case "&6" -> "#ffaa00";
            case "&7" -> "#aaaaaa";
            case "&8" -> "#555555";
            case "&9" -> "#5555ff";
            case "&a" -> "#55ff55";
            case "&b" -> "#55ffff";
            case "&c" -> "#ff5555";
            case "&d" -> "#ff55ff";
            case "&e" -> "#ffff55";
            case "&f" -> "#ffffff";
            default -> "#bbbbbb";
        };
    }

    private record Rank(String key, String name, String prefix, String color, int weight) {
    }

    private record WorldData(String key, String name, String group) {
        private static WorldData none() {
            return new WorldData("", "", "");
        }
    }

    private record TeamData(String id, String name, String role, long joinedAt) {
        private static TeamData none() {
            return new TeamData("", "", "", 0L);
        }
    }
}
