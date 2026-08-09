package net.mineacle.core.webprofiles.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
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
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WebProfileSyncService {

    private final Core core;
    private final FileConfiguration config;
    private final WebProfileRepository repository;
    private final LuckPerms luckPerms;
    private final Set<UUID> pendingRankRefreshes =
            ConcurrentHashMap.newKeySet();

    private BukkitTask syncTask;
    private EventSubscription<UserDataRecalculateEvent>
            luckPermsRankSubscription;

    public WebProfileSyncService(
            Core core,
            FileConfiguration config,
            WebProfileRepository repository
    ) {
        this.core = core;
        this.config = config;
        this.repository = repository;

        RegisteredServiceProvider<LuckPerms> registration =
                core.getServer()
                        .getServicesManager()
                        .getRegistration(LuckPerms.class);

        if (registration == null) {
            throw new IllegalStateException(
                    "LuckPerms API service is unavailable"
            );
        }

        this.luckPerms = registration.getProvider();
    }

    public void start() {
        if (!config.getBoolean("enabled", true)) {
            core.getLogger().info("Web profiles are disabled");
            return;
        }

        repository.initialize();

        long intervalTicks = Math.max(
                20L,
                config.getLong(
                        "sync.interval-seconds",
                        120L
                ) * 20L
        );

        syncTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        this::syncAll,
                        80L,
                        intervalTicks
                );

        luckPermsRankSubscription =
                luckPerms.getEventBus().subscribe(
                        core,
                        UserDataRecalculateEvent.class,
                        event -> queueLuckPermsRankRefresh(
                                event.getUser().getUniqueId()
                        )
                );

        core.getLogger().info(
                "Web profile sync enabled with LuckPerms rank bridge"
        );
    }

    public void stop() {
        if (luckPermsRankSubscription != null) {
            luckPermsRankSubscription.close();
            luckPermsRankSubscription = null;
        }

        pendingRankRefreshes.clear();

        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }

        if (config.getBoolean(
                "sync.mark-offline-on-disable",
                true
        )) {
            core.getServer()
                    .getScheduler()
                    .runTaskAsynchronously(
                            core,
                            repository::markOffline
                    );
        }
    }

    // LuckPerms events are asynchronous. Never read Bukkit Player state
    // inside the callback. Collapse repeated recalculation events by UUID
    // and move the actual player/profile work back to the server thread.
    private void queueLuckPermsRankRefresh(UUID uuid) {
        if (uuid == null
                || !core.isEnabled()
                || !pendingRankRefreshes.add(uuid)) {
            return;
        }

        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    pendingRankRefreshes.remove(uuid);

                    Player player = Bukkit.getPlayer(uuid);

                    if (player != null && player.isOnline()) {
                        syncPlayer(player, true);
                    }
                }
        );
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

        if (config.getBoolean(
                "sync.include-known-offline-players",
                true
        )) {
            int limit = Math.max(
                    1,
                    config.getInt(
                            "sync.offline-player-pull-limit",
                            10000
                    )
            );
            int count = 0;

            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                ids.add(player.getUniqueId());

                if (++count >= limit) {
                    break;
                }
            }
        }

        int leaderboardPull = Math.max(
                100,
                config.getInt(
                        "sync.leaderboard-pull-limit",
                        500
                )
        );

        for (Map.Entry<UUID, Long> entry
                : economy.topBalances(leaderboardPull)) {
            ids.add(entry.getKey());
        }

        stats.topPlaytime(leaderboardPull)
                .forEach(profile -> ids.add(profile.uuid()));
        stats.topKills(leaderboardPull)
                .forEach(profile -> ids.add(profile.uuid()));
        stats.topDeaths(leaderboardPull)
                .forEach(profile -> ids.add(profile.uuid()));

        List<WebProfileRecord> records = new ArrayList<>();

        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            WebProfileRecord record = record(
                    id,
                    player,
                    stats,
                    economy,
                    player != null
            );

            if (record != null) {
                records.add(record);
            }
        }

        if (!records.isEmpty()) {
            core.getServer()
                    .getScheduler()
                    .runTaskAsynchronously(
                            core,
                            () -> repository.upsertAll(records)
                    );
        }
    }

    public void syncPlayer(
            Player player,
            boolean online
    ) {
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
            core.getServer()
                    .getScheduler()
                    .runTaskAsynchronously(
                            core,
                            () -> repository.upsertAll(
                                    List.of(record)
                            )
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

        int moneyRank = balance <= 0L
                ? 0
                : moneyRank(uuid, economy);
        int killsRank = kills <= 0L
                ? 0
                : stats.rankKills(uuid);
        int playtimeRank = playtime <= 0L
                ? 0
                : stats.rankPlaytime(uuid);

        String displayName = player != null
                ? DisplayNames.displayName(player)
                : username;
        Rank rank = rank(uuid, player);
        WorldData world = player != null
                ? worldData(player.getWorld())
                : WorldData.none();
        TeamData team = teamData(uuid);

        long firstJoinedAt = offline.getFirstPlayed() <= 0L
                ? now
                : offline.getFirstPlayed();
        long lastSeen = online
                ? now
                : offline.getLastSeen();

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
                deaths <= 0L
                        ? kills
                        : Math.round(
                                (kills / (double) deaths) * 100.0D
                        ) / 100.0D,
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
            return new WorldData(
                    key,
                    name,
                    group == null ? "" : group
            );
        }

        return switch (key.toLowerCase(Locale.ROOT)) {
            case "spawn1" ->
                    new WorldData(
                            key,
                            "Spawn 1",
                            "spawn"
                    );
            case "spawn2" ->
                    new WorldData(
                            key,
                            "Spawn 2",
                            "spawn"
                    );
            case "spawn3" ->
                    new WorldData(
                            key,
                            "Spawn 3",
                            "spawn"
                    );
            case "origins" ->
                    new WorldData(
                            key,
                            "Overworld",
                            "survival"
                    );
            case "world_nether", "origins_nether" ->
                    new WorldData(
                            key,
                            "Nether",
                            "survival"
                    );
            case "world_the_end", "origins_the_end" ->
                    new WorldData(
                            key,
                            "End",
                            "survival"
                    );
            default ->
                    new WorldData(
                            key,
                            config.getString(
                                    "worlds.default-name",
                                    key
                            ),
                            config.getString(
                                    "worlds.default-group",
                                    "other"
                            )
                    );
        };
    }

    private TeamData teamData(UUID uuid) {
        TeamService teamService = TeamsModule.teamService();

        if (teamService == null) {
            return TeamData.none();
        }

        TeamRecord team =
                teamService.getTeamByPlayer(uuid);
        TeamMemberRecord member =
                teamService.getMember(uuid);

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

        String normalized =
                roleName.toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "MVP" -> "MVP";
            case "VIP" -> "VIP";
            case "FOUNDER" -> "Founder";
            case "ADMIN" -> "Admin";
            case "MEMBER" -> "Member";
            default ->
                    normalized.charAt(0)
                            + normalized
                            .substring(1)
                            .toLowerCase(Locale.ROOT);
        };
    }

    private int moneyRank(
            UUID uuid,
            EconomyService economy
    ) {
        List<Map.Entry<UUID, Long>> entries =
                economy.topBalances(Integer.MAX_VALUE)
                        .stream()
                        .filter(entry ->
                                entry.getValue() != null
                                        && entry.getValue() > 0L
                        )
                        .toList();

        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i)
                    .getKey()
                    .equals(uuid)) {
                return i + 1;
            }
        }

        return 0;
    }

    private Rank rank(
            UUID uuid,
            Player player
    ) {
        if (player != null && player.isOnline()) {
            return luckPermsRank(player);
        }

        return repository.findRank(uuid)
                .map(this::storedRank)
                .filter(rank ->
                        !isRetiredRank(rank.key())
                )
                .orElseGet(this::defaultRank);
    }

    // Resolve the public Mineacle rank directly from LuckPerms.
    // LuckPerms owns group membership, inheritance and priority/weight.
    // MineacleCore maps that result into normalized website fields.
    // Non-contextual query options keep the public rank global across worlds.
    private Rank luckPermsRank(Player player) {
        ConfigurationSection mappings = rankMappings();

        if (mappings == null) {
            return defaultRank();
        }

        User user = luckPerms
                .getPlayerAdapter(Player.class)
                .getUser(player);
        QueryOptions queryOptions = QueryOptions.nonContextual();
        Map<String, Group> inheritedGroups = new LinkedHashMap<>();

        for (Group group : user.getInheritedGroups(queryOptions)) {
            if (group == null
                    || group.getName() == null
                    || group.getName().isBlank()) {
                continue;
            }

            inheritedGroups.put(
                    group.getName().trim().toLowerCase(Locale.ROOT),
                    group
            );
        }

        Rank best = defaultRank();

        for (String key : mappings.getKeys(false)) {
            String normalizedKey = key.trim().toLowerCase(Locale.ROOT);

            if (isRetiredRank(normalizedKey)) {
                continue;
            }

            ConfigurationSection section =
                    mappings.getConfigurationSection(key);

            if (section == null
                    || !requiredGroupsPresent(section, inheritedGroups)) {
                continue;
            }

            Group matchedGroup = bestMatchingLuckPermsGroup(
                    normalizedKey,
                    section,
                    inheritedGroups
            );

            if (matchedGroup == null) {
                continue;
            }

            int luckPermsWeight = matchedGroup.getWeight().orElse(0);

            if (luckPermsWeight <= best.weight()) {
                continue;
            }

            best = new Rank(
                    normalizedKey,
                    section.getString("name", key),
                    section.getString("prefix", ""),
                    normalizeHex(
                            section.getString(
                                    "color",
                                    "#bbbbbb"
                            )
                    ),
                    luckPermsWeight
            );
        }

        return best;
    }

    // New configs use rank.mappings. The old rank.permission-ranks section
    // remains accepted as a deployment safety net; its keys are interpreted
    // as LuckPerms group names and old Mineacle config weights are ignored.
    private ConfigurationSection rankMappings() {
        ConfigurationSection mappings =
                config.getConfigurationSection("rank.mappings");

        if (mappings != null) {
            return mappings;
        }

        return config.getConfigurationSection(
                "rank.permission-ranks"
        );
    }

    private Group bestMatchingLuckPermsGroup(
            String rankKey,
            ConfigurationSection section,
            Map<String, Group> inheritedGroups
    ) {
        if (inheritedGroups.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> acceptedGroups = new LinkedHashSet<>();

        addGroupAlias(acceptedGroups, rankKey);
        addGroupAlias(
                acceptedGroups,
                section.getString("luckperms-group", "")
        );

        for (String group :
                section.getStringList("luckperms-groups")) {
            addGroupAlias(acceptedGroups, group);
        }

        // Compatibility with the short-lived v32 draft config names.
        addGroupAlias(
                acceptedGroups,
                section.getString("group", "")
        );

        for (String group : section.getStringList("groups")) {
            addGroupAlias(acceptedGroups, group);
        }

        Group best = null;
        int bestWeight = Integer.MIN_VALUE;

        for (String accepted : acceptedGroups) {
            Group candidate = inheritedGroups.get(accepted);

            if (candidate == null) {
                continue;
            }

            int candidateWeight = candidate.getWeight().orElse(0);

            if (best == null || candidateWeight > bestWeight) {
                best = candidate;
                bestWeight = candidateWeight;
            }
        }

        return best;
    }

    // Composite ranks can require other inherited groups.
    // Media requires both "media" and "plus" before Core writes Media +.
    private boolean requiredGroupsPresent(
            ConfigurationSection section,
            Map<String, Group> inheritedGroups
    ) {
        LinkedHashSet<String> required = new LinkedHashSet<>();

        addGroupAlias(
                required,
                section.getString("requires-group", "")
        );

        for (String group :
                section.getStringList("requires-groups")) {
            addGroupAlias(required, group);
        }

        for (String requiredGroup : required) {
            if (!inheritedGroups.containsKey(requiredGroup)) {
                return false;
            }
        }

        return true;
    }

    private void addGroupAlias(
            Set<String> target,
            String value
    ) {
        if (value == null || value.isBlank()) {
            return;
        }

        target.add(
                value.trim().toLowerCase(Locale.ROOT)
        );
    }

    private boolean isRetiredRank(String key) {
        if (key == null) {
            return false;
        }

        String normalized =
                key.trim().toLowerCase(Locale.ROOT);

        return normalized.equals("developer")
                || normalized.equals("dev");
    }

    private Rank storedRank(
            WebProfileRepository.StoredRank stored
    ) {
        Rank fallback = defaultRank();

        String key =
                stored.key() == null
                        || stored.key().isBlank()
                        ? fallback.key()
                        : stored.key();

        if (isRetiredRank(key)) {
            return fallback;
        }

        String name =
                stored.name() == null
                        || stored.name().isBlank()
                        ? fallback.name()
                        : stored.name();
        String prefix =
                stored.prefix() == null
                        ? fallback.prefix()
                        : stored.prefix();
        String color =
                stored.color() == null
                        || stored.color().isBlank()
                        ? fallback.color()
                        : normalizeHex(stored.color());

        return new Rank(
                key,
                name,
                prefix,
                color,
                stored.weight()
        );
    }

    private Rank defaultRank() {
        String key = config.getString(
                "rank.default-key",
                "default"
        );
        int weight = luckPermsGroupWeight(key);

        return new Rank(
                key,
                config.getString(
                        "rank.default-name",
                        "Member"
                ),
                config.getString(
                        "rank.default-prefix",
                        ""
                ),
                normalizeHex(
                        config.getString(
                                "rank.default-color",
                                "#bbbbbb"
                        )
                ),
                weight
        );
    }

    private int luckPermsGroupWeight(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return 0;
        }

        Group group = luckPerms
                .getGroupManager()
                .getGroup(
                        groupName.trim().toLowerCase(Locale.ROOT)
                );

        return group == null
                ? 0
                : group.getWeight().orElse(0);
    }

    private String normalizeHex(String color) {
        if (color == null || color.isBlank()) {
            return "#bbbbbb";
        }

        String value = color.trim();

        if (value.matches(
                "(?i)^#[0-9a-f]{6}$"
        )) {
            return value.toLowerCase(Locale.ROOT);
        }

        return switch (
                value.toLowerCase(Locale.ROOT)
        ) {
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

    private record Rank(
            String key,
            String name,
            String prefix,
            String color,
            int weight
    ) {
    }

    private record WorldData(
            String key,
            String name,
            String group
    ) {
        private static WorldData none() {
            return new WorldData(
                    "",
                    "",
                    ""
            );
        }
    }

    private record TeamData(
            String id,
            String name,
            String role,
            long joinedAt
    ) {
        private static TeamData none() {
            return new TeamData(
                    "",
                    "",
                    "",
                    0L
            );
        }
    }
}
