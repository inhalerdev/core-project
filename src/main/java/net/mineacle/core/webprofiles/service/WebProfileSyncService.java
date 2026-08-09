package net.mineacle.core.webprofiles.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.RankDisplayResolver;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.StatsModule;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.stats.service.StatsStorageService.StatProfile;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import net.mineacle.core.webprofiles.model.WebProfileRecord;
import net.mineacle.core.webprofiles.storage.WebProfileRepository;
import net.mineacle.core.webprofiles.storage.WebRankRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class WebProfileSyncService {

    private static final int DEFAULT_OFFLINE_BATCH_SIZE = 250;
    private static final long DEFAULT_OFFLINE_REFRESH_SECONDS = 1800L;
    private static final int MAX_OFFLINE_BATCH_SIZE = 2000;
    private static final long INCREMENTAL_FLUSH_DELAY_TICKS = 10L;

    private final Core core;
    private final FileConfiguration config;
    private final WebProfileRepository repository;
    private final WebRankRepository rankRepository;
    private final LuckPerms luckPerms;
    private final ExecutorService ioExecutor;
    private final AtomicBoolean fullSyncInFlight =
            new AtomicBoolean();
    private final AtomicBoolean fullSyncRequested =
            new AtomicBoolean();
    private final AtomicBoolean incrementalFlushScheduled =
            new AtomicBoolean();
    private final Map<UUID, WebProfileRecord> pendingPlayerRecords =
            new ConcurrentHashMap<>();
    private final Map<UUID, WebRankRepository.RankUpdate>
            pendingRankUpdates =
            new ConcurrentHashMap<>();

    private BukkitTask syncTask;
    private BukkitTask incrementalFlushTask;
    private EventSubscription<UserDataRecalculateEvent>
            luckPermsRankSubscription;
    private RankingSnapshot rankings =
            RankingSnapshot.empty();
    private List<OfflineSnapshot> offlinePopulation =
            List.of();
    private Map<UUID, OfflineSnapshot> offlineById =
            Map.of();
    private int offlineCursor;
    private long offlinePopulationRefreshAt;
    private volatile boolean running;

    public WebProfileSyncService(
            Core core,
            FileConfiguration config,
            WebProfileRepository repository
    ) {
        this.core = core;
        this.config = config;
        this.repository = repository;
        this.rankRepository = new WebRankRepository(
                core,
                config
        );
        this.ioExecutor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(
                                    runnable,
                                    "Mineacle-WebProfileIO"
                            );
                            thread.setDaemon(true);
                            return thread;
                        }
                );

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
            core.getLogger().info(
                    "Web profiles are disabled"
            );
            return;
        }

        repository.initialize();
        running = true;

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
                                event.getUser()
                        )
                );

        core.getLogger().info(
                "Web profile sync enabled with serialized database I/O"
        );
    }

    public void stop() {
        boolean wasRunning = running;
        running = false;

        if (luckPermsRankSubscription != null) {
            luckPermsRankSubscription.close();
            luckPermsRankSubscription = null;
        }

        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }

        if (incrementalFlushTask != null) {
            incrementalFlushTask.cancel();
            incrementalFlushTask = null;
        }

        incrementalFlushScheduled.set(false);
        fullSyncRequested.set(false);

        flushIncrementalNow();

        if (wasRunning
                && config.getBoolean(
                        "sync.mark-offline-on-disable",
                        true
                )) {
            submitIo(repository::markOffline);
        }

        ioExecutor.shutdown();

        try {
            if (!ioExecutor.awaitTermination(
                    5L,
                    TimeUnit.SECONDS
            )) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    // LuckPerms recalculation events are asynchronous. Bukkit state and
    // public-rank resolution return to the server thread; the database write
    // is then serialized through the same I/O worker as profile batches.
    private void queueLuckPermsRankRefresh(
            User recalculatedUser
    ) {
        if (!core.isEnabled()) {
            return;
        }

        UUID uuid = recalculatedUser.getUniqueId();

        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (!running || !core.isEnabled()) {
                        return;
                    }

                    Player player = Bukkit.getPlayer(uuid);

                    if (player != null && player.isOnline()) {
                        syncPlayer(player, true);
                        return;
                    }

                    User latestUser = luckPerms
                            .getUserManager()
                            .getUser(uuid);
                    User rankSource = latestUser == null
                            ? recalculatedUser
                            : latestUser;
                    WebRank rank = luckPermsRank(rankSource);
                    String username =
                            rankSource.getUsername();

                    queueRankUpdate(
                            new WebRankRepository.RankUpdate(
                                    uuid,
                                    username,
                                    rank.key(),
                                    rank.name(),
                                    rank.prefix(),
                                    rank.color(),
                                    rank.weight()
                            )
                    );
                }
        );
    }

    public void syncAll() {
        if (!running || !core.isEnabled()) {
            return;
        }

        if (!fullSyncInFlight.compareAndSet(
                false,
                true
        )) {
            fullSyncRequested.set(true);
            return;
        }

        try {
            SyncBatch batch = createSyncBatch();

            if (batch.drafts().isEmpty()) {
                finishFullSync();
                return;
            }

            if (!submitIo(
                    () -> persistBatch(batch)
            )) {
                finishFullSync();
            }
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not build web profile sync snapshot",
                    exception
            );
            finishFullSync();
        }
    }

    public void syncPlayer(
            Player player,
            boolean online
    ) {
        if (!running
                || !core.isEnabled()) {
            return;
        }

        StatsService stats = StatsModule.statsService();
        EconomyService economy =
                EconomyModule.economyService();

        if (stats == null || economy == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        OfflineSnapshot offline =
                snapshot(player);
        long playtimeSeconds =
                stats.playtimeSeconds(uuid);
        PlayerStatsData statsData =
                new PlayerStatsData(
                        stats.kills(uuid),
                        stats.deaths(uuid),
                        playtimeSeconds
                );
        WebRank rank = luckPermsRank(player);
        ProfileDraft draft = draft(
                uuid,
                player,
                offline,
                stats,
                statsData,
                economy,
                online && player.isOnline(),
                rankings,
                rank
        );

        if (draft == null) {
            return;
        }

        WebProfileRecord record =
                draft.toRecord(rank);

        queuePlayerRecord(record);
    }

    private SyncBatch createSyncBatch() {
        StatsService stats = StatsModule.statsService();
        EconomyService economy =
                EconomyModule.economyService();

        if (stats == null || economy == null) {
            return SyncBatch.empty(defaultRank());
        }

        var balances =
                economy.topBalances(Integer.MAX_VALUE);
        List<StatProfile> playtime =
                stats.topPlaytime(Integer.MAX_VALUE);
        List<StatProfile> kills =
                new ArrayList<>(playtime);
        List<StatProfile> deaths =
                new ArrayList<>(playtime);

        kills.sort(
                Comparator
                        .comparingLong(StatProfile::kills)
                        .thenComparingLong(
                                StatProfile::playtimeSeconds
                        )
                        .reversed()
        );
        deaths.sort(
                Comparator
                        .comparingLong(StatProfile::deaths)
                        .thenComparingLong(
                                StatProfile::playtimeSeconds
                        )
                        .reversed()
        );

        Map<UUID, PlayerStatsData> statsSnapshot =
                new HashMap<>(
                        Math.max(
                                16,
                                playtime.size() * 2
                        )
                );

        for (StatProfile profile : playtime) {
            statsSnapshot.put(
                    profile.uuid(),
                    new PlayerStatsData(
                            profile.kills(),
                            profile.deaths(),
                            profile.playtimeSeconds()
                    )
            );
        }

        Map<UUID, Integer> moneyRanks =
                new HashMap<>();
        int moneyPosition = 0;

        for (Map.Entry<UUID, Long> entry :
                balances) {
            Long value = entry.getValue();

            if (value == null || value <= 0L) {
                continue;
            }

            moneyPosition++;
            moneyRanks.put(
                    entry.getKey(),
                    moneyPosition
            );
        }

        Map<UUID, Integer> killRanks =
                new HashMap<>(
                        Math.max(
                                16,
                                kills.size() * 2
                        )
                );

        for (int index = 0;
             index < kills.size();
             index++) {
            killRanks.put(
                    kills.get(index).uuid(),
                    index + 1
            );
        }

        Map<UUID, Integer> playtimeRanks =
                new HashMap<>(
                        Math.max(
                                16,
                                playtime.size() * 2
                        )
                );

        for (int index = 0;
             index < playtime.size();
             index++) {
            playtimeRanks.put(
                    playtime.get(index).uuid(),
                    index + 1
            );
        }

        RankingSnapshot rankingSnapshot =
                new RankingSnapshot(
                        Map.copyOf(moneyRanks),
                        Map.copyOf(killRanks),
                        Map.copyOf(playtimeRanks)
                );
        rankings = rankingSnapshot;

        LinkedHashSet<UUID> ids =
                new LinkedHashSet<>();
        Map<UUID, OfflineSnapshot> metadata =
                new HashMap<>();

        for (Player player :
                Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            ids.add(uuid);
            metadata.put(
                    uuid,
                    snapshot(player)
            );
        }

        addOfflineBatch(ids, metadata);

        int leaderboardPull = Math.max(
                100,
                config.getInt(
                        "sync.leaderboard-pull-limit",
                        500
                )
        );

        addBalanceLeaders(
                ids,
                balances,
                leaderboardPull
        );
        int killLeaderCount = Math.min(
                leaderboardPull,
                kills.size()
        );

        for (int index = 0;
             index < killLeaderCount;
             index++) {
            ids.add(kills.get(index).uuid());
        }

        int playtimeLeaderCount = Math.min(
                leaderboardPull,
                playtime.size()
        );

        for (int index = 0;
             index < playtimeLeaderCount;
             index++) {
            ids.add(
                    playtime.get(index).uuid()
            );
        }

        int deathLeaderCount = Math.min(
                leaderboardPull,
                deaths.size()
        );

        for (int index = 0;
             index < deathLeaderCount;
             index++) {
            ids.add(deaths.get(index).uuid());
        }

        List<ProfileDraft> drafts =
                new ArrayList<>(ids.size());
        Set<UUID> unresolvedRanks =
                new LinkedHashSet<>();

        for (UUID uuid : ids) {
            Player player = Bukkit.getPlayer(uuid);
            OfflineSnapshot offline =
                    metadata.get(uuid);

            if (offline == null) {
                offline = offlineById.get(uuid);
            }

            if (offline == null) {
                offline = snapshot(
                        Bukkit.getOfflinePlayer(uuid)
                );
            }

            WebRank resolvedRank = null;

            if (player != null && player.isOnline()) {
                resolvedRank = luckPermsRank(player);
            } else {
                User loadedUser = luckPerms
                        .getUserManager()
                        .getUser(uuid);

                if (loadedUser != null) {
                    resolvedRank =
                            luckPermsRank(loadedUser);
                }
            }

            PlayerStatsData statsData =
                    statsSnapshot.getOrDefault(
                            uuid,
                            PlayerStatsData.ZERO
                    );

            ProfileDraft draft = draft(
                    uuid,
                    player,
                    offline,
                    stats,
                    statsData,
                    economy,
                    player != null
                            && player.isOnline(),
                    rankingSnapshot,
                    resolvedRank
            );

            if (draft == null) {
                continue;
            }

            drafts.add(draft);

            if (resolvedRank == null) {
                unresolvedRanks.add(uuid);
            }
        }

        return new SyncBatch(
                List.copyOf(drafts),
                Set.copyOf(unresolvedRanks),
                defaultRank()
        );
    }

    private void persistBatch(SyncBatch batch) {
        try {
            Map<UUID, WebRankRepository.StoredRank>
                    storedRanks =
                    rankRepository.findRanks(
                            batch.unresolvedRankIds()
                    );
            List<WebProfileRecord> records =
                    new ArrayList<>(
                            batch.drafts().size()
                    );

            for (ProfileDraft draft :
                    batch.drafts()) {
                WebRank rank = draft.resolvedRank();

                if (rank == null) {
                    WebRankRepository.StoredRank stored =
                            storedRanks.get(
                                    draft.uuid()
                            );
                    rank = stored == null
                            ? batch.defaultRank()
                            : storedRank(
                                    stored,
                                    batch.defaultRank()
                            );
                }

                records.add(
                        draft.toRecord(rank)
                );
            }

            if (!records.isEmpty()) {
                repository.upsertAll(records);
            }
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Web profile batch sync failed",
                    exception
            );
        } finally {
            finishFullSyncFromIo();
        }
    }

    private void finishFullSyncFromIo() {
        fullSyncInFlight.set(false);

        if (!fullSyncRequested.getAndSet(false)
                || !running
                || !core.isEnabled()) {
            return;
        }

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        this::syncAll
                );
    }

    private void finishFullSync() {
        fullSyncInFlight.set(false);

        if (fullSyncRequested.getAndSet(false)
                && running
                && core.isEnabled()) {
            core.getServer()
                    .getScheduler()
                    .runTask(
                            core,
                            this::syncAll
                    );
        }
    }

    private void queuePlayerRecord(
            WebProfileRecord record
    ) {
        pendingPlayerRecords.put(
                record.uuid(),
                record
        );
        scheduleIncrementalFlush();
    }

    private void queueRankUpdate(
            WebRankRepository.RankUpdate update
    ) {
        pendingRankUpdates.put(
                update.uuid(),
                update
        );
        scheduleIncrementalFlush();
    }

    private void scheduleIncrementalFlush() {
        if (!running
                || !core.isEnabled()
                || !incrementalFlushScheduled.compareAndSet(
                false,
                true
        )) {
            return;
        }

        incrementalFlushTask = core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        this::flushIncremental,
                        INCREMENTAL_FLUSH_DELAY_TICKS
                );
    }

    private void flushIncremental() {
        incrementalFlushTask = null;
        incrementalFlushScheduled.set(false);

        flushIncrementalNow();

        if (running
                && core.isEnabled()
                && (!pendingPlayerRecords.isEmpty()
                || !pendingRankUpdates.isEmpty())) {
            scheduleIncrementalFlush();
        }
    }

    private void flushIncrementalNow() {
        List<WebProfileRecord> records =
                drainPlayerRecords();
        List<WebRankRepository.RankUpdate> rankUpdates =
                drainRankUpdates();

        if (records.isEmpty()
                && rankUpdates.isEmpty()) {
            return;
        }

        boolean accepted = submitIo(
                () -> {
                    if (!records.isEmpty()) {
                        repository.upsertAll(records);
                    }

                    if (!rankUpdates.isEmpty()) {
                        rankRepository.upsertRanks(
                                rankUpdates
                        );
                    }
                }
        );

        if (!accepted && running) {
            for (WebProfileRecord record : records) {
                pendingPlayerRecords.put(
                        record.uuid(),
                        record
                );
            }

            for (WebRankRepository.RankUpdate update :
                    rankUpdates) {
                pendingRankUpdates.put(
                        update.uuid(),
                        update
                );
            }
        }
    }

    private List<WebProfileRecord> drainPlayerRecords() {
        if (pendingPlayerRecords.isEmpty()) {
            return List.of();
        }

        List<WebProfileRecord> records =
                new ArrayList<>(
                        pendingPlayerRecords.size()
                );

        for (Map.Entry<UUID, WebProfileRecord> entry :
                pendingPlayerRecords.entrySet()) {
            if (pendingPlayerRecords.remove(
                    entry.getKey(),
                    entry.getValue()
            )) {
                records.add(entry.getValue());
            }
        }

        return List.copyOf(records);
    }

    private List<WebRankRepository.RankUpdate>
    drainRankUpdates() {
        if (pendingRankUpdates.isEmpty()) {
            return List.of();
        }

        List<WebRankRepository.RankUpdate> updates =
                new ArrayList<>(
                        pendingRankUpdates.size()
                );

        for (Map.Entry<UUID, WebRankRepository.RankUpdate>
                entry : pendingRankUpdates.entrySet()) {
            if (pendingRankUpdates.remove(
                    entry.getKey(),
                    entry.getValue()
            )) {
                updates.add(entry.getValue());
            }
        }

        return List.copyOf(updates);
    }

    private boolean submitIo(Runnable task) {
        if (ioExecutor.isShutdown()) {
            return false;
        }

        try {
            ioExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void addOfflineBatch(
            Set<UUID> ids,
            Map<UUID, OfflineSnapshot> metadata
    ) {
        if (!config.getBoolean(
                "sync.include-known-offline-players",
                true
        )) {
            return;
        }

        long now = System.currentTimeMillis();

        if (offlinePopulation.isEmpty()
                || now >= offlinePopulationRefreshAt) {
            refreshOfflinePopulation(now);
        }

        if (offlinePopulation.isEmpty()) {
            return;
        }

        int batchSize = Math.clamp(
                config.getInt(
                        "sync.offline-player-batch-size",
                        DEFAULT_OFFLINE_BATCH_SIZE
                ),
                1,
                MAX_OFFLINE_BATCH_SIZE
        );

        int count = Math.min(
                batchSize,
                offlinePopulation.size()
        );

        for (int index = 0; index < count; index++) {
            OfflineSnapshot offline =
                    offlinePopulation.get(
                            offlineCursor
                    );

            offlineCursor++;
            if (offlineCursor
                    >= offlinePopulation.size()) {
                offlineCursor = 0;
            }

            if (Bukkit.getPlayer(
                    offline.uuid()
            ) != null) {
                continue;
            }

            ids.add(offline.uuid());
            metadata.put(
                    offline.uuid(),
                    offline
            );
        }
    }

    private void refreshOfflinePopulation(long now) {
        int limit = Math.max(
                1,
                config.getInt(
                        "sync.offline-player-pull-limit",
                        10000
                )
        );
        OfflinePlayer[] knownPlayers =
                Bukkit.getOfflinePlayers();
        List<OfflineSnapshot> population =
                new ArrayList<>(
                        Math.min(
                                limit,
                                knownPlayers.length
                        )
                );
        Map<UUID, OfflineSnapshot> byId =
                new HashMap<>();

        int count = 0;

        for (OfflinePlayer offline :
                knownPlayers) {
            OfflineSnapshot snapshot =
                    snapshot(offline);

            population.add(snapshot);
            byId.put(
                    snapshot.uuid(),
                    snapshot
            );

            count++;
            if (count >= limit) {
                break;
            }
        }

        offlinePopulation =
                List.copyOf(population);
        offlineById =
                Map.copyOf(byId);

        if (offlineCursor
                >= offlinePopulation.size()) {
            offlineCursor = 0;
        }

        long refreshSeconds = Math.max(
                60L,
                config.getLong(
                        "sync.offline-player-refresh-seconds",
                        DEFAULT_OFFLINE_REFRESH_SECONDS
                )
        );

        offlinePopulationRefreshAt =
                now + refreshSeconds * 1000L;
    }

    private void addBalanceLeaders(
            Set<UUID> ids,
            List<Map.Entry<UUID, Long>> balances,
            int limit
    ) {
        int count = Math.min(
                limit,
                balances.size()
        );

        for (int index = 0; index < count; index++) {
            ids.add(
                    balances.get(index).getKey()
            );
        }
    }

    private ProfileDraft draft(
            UUID uuid,
            Player player,
            OfflineSnapshot offline,
            StatsService stats,
            PlayerStatsData statsData,
            EconomyService economy,
            boolean online,
            RankingSnapshot rankingSnapshot,
            WebRank resolvedRank
    ) {
        String username = offline.username();

        if (username == null || username.isBlank()) {
            return null;
        }

        long now = System.currentTimeMillis();
        long balance = economy.getBalanceCents(uuid);
        long kills = statsData.kills();
        long deaths = statsData.deaths();
        long playtime = statsData.playtimeSeconds();

        int moneyRank = balance <= 0L
                ? 0
                : rankingSnapshot.moneyRank(uuid);
        int killsRank = kills <= 0L
                ? 0
                : rankingSnapshot.killsRank(uuid);
        int playtimeRank = playtime <= 0L
                ? 0
                : rankingSnapshot.playtimeRank(uuid);

        String displayName = player != null
                ? DisplayNames.displayName(player)
                : username;
        WorldData world = player != null
                ? worldData(player.getWorld())
                : WorldData.none();
        TeamData team = teamData(uuid);

        long firstJoinedAt =
                offline.firstPlayed() <= 0L
                        ? now
                        : offline.firstPlayed();
        long lastSeen = online
                ? now
                : offline.lastSeen();

        if (lastSeen <= 0L) {
            lastSeen = now;
        }

        return new ProfileDraft(
                uuid,
                username,
                displayName,
                resolvedRank,
                world,
                team,
                balance,
                economy.format(balance),
                playtime,
                stats.formatPlaytime(playtime),
                kills,
                deaths,
                deaths <= 0L
                        ? kills
                        : Math.round(
                                (kills / (double) deaths)
                                        * 100.0D
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

    private OfflineSnapshot snapshot(
            OfflinePlayer player
    ) {
        return new OfflineSnapshot(
                player.getUniqueId(),
                player.getName(),
                player.getFirstPlayed(),
                player.getLastSeen()
        );
    }

    private WorldData worldData(World world) {
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


    private WebRank luckPermsRank(Player player) {
        return webRank(
                RankDisplayResolver.resolve(player)
        );
    }

    private WebRank luckPermsRank(User user) {
        return webRank(
                RankDisplayResolver.resolveUser(user)
        );
    }

    private WebRank webRank(
            RankDisplayResolver.DisplayRank rank
    ) {
        return new WebRank(
                rank.key(),
                rank.name(),
                rank.webPrefix(),
                rank.color(),
                rank.weight()
        );
    }

    private boolean isRetiredRank(String key) {
        String normalized =
                key.trim().toLowerCase(Locale.ROOT);

        return normalized.equals("developer")
                || normalized.equals("dev");
    }

    private WebRank storedRank(
            WebRankRepository.StoredRank stored,
            WebRank fallback
    ) {
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
                        : stored.color();

        return new WebRank(
                key,
                name,
                prefix,
                color,
                stored.weight()
        );
    }

    private WebRank defaultRank() {
        return webRank(
                RankDisplayResolver.defaultRank()
        );
    }

    private record WebRank(
            String key,
            String name,
            String prefix,
            String color,
            int weight
    ) {
    }

    private record PlayerStatsData(
            long kills,
            long deaths,
            long playtimeSeconds
    ) {
        private static final PlayerStatsData ZERO =
                new PlayerStatsData(
                        0L,
                        0L,
                        0L
                );
    }

    private record OfflineSnapshot(
            UUID uuid,
            String username,
            long firstPlayed,
            long lastSeen
    ) {
    }

    private record RankingSnapshot(
            Map<UUID, Integer> moneyRanks,
            Map<UUID, Integer> killsRanks,
            Map<UUID, Integer> playtimeRanks
    ) {
        private static RankingSnapshot empty() {
            return new RankingSnapshot(
                    Map.of(),
                    Map.of(),
                    Map.of()
            );
        }

        private int moneyRank(UUID uuid) {
            return moneyRanks.getOrDefault(
                    uuid,
                    0
            );
        }

        private int killsRank(UUID uuid) {
            return killsRanks.getOrDefault(
                    uuid,
                    0
            );
        }

        private int playtimeRank(UUID uuid) {
            return playtimeRanks.getOrDefault(
                    uuid,
                    0
            );
        }
    }

    private record SyncBatch(
            List<ProfileDraft> drafts,
            Set<UUID> unresolvedRankIds,
            WebRank defaultRank
    ) {
        private static SyncBatch empty(
                WebRank defaultRank
        ) {
            return new SyncBatch(
                    List.of(),
                    Set.of(),
                    defaultRank
            );
        }
    }

    private record ProfileDraft(
            UUID uuid,
            String username,
            String displayName,
            WebRank resolvedRank,
            WorldData world,
            TeamData team,
            long balance,
            String balanceFormatted,
            long playtimeSeconds,
            String playtimeFormatted,
            long kills,
            long deaths,
            double kd,
            int moneyRank,
            int killsRank,
            int playtimeRank,
            long firstJoinedAt,
            long lastSeen,
            boolean online,
            long updatedAt
    ) {
        private WebProfileRecord toRecord(
                WebRank rank
        ) {
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
                    balanceFormatted,
                    playtimeSeconds,
                    playtimeFormatted,
                    kills,
                    deaths,
                    kd,
                    moneyRank,
                    killsRank,
                    playtimeRank,
                    firstJoinedAt,
                    lastSeen,
                    online,
                    updatedAt
            );
        }
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