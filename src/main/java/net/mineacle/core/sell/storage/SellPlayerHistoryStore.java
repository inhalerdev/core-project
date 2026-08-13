package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import net.mineacle.core.sell.model.SellHistoryEntry;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Database-backed aggregate Sell history with a small LRU hot cache.
 * <p>
 * Permanent player history belongs in SQL. Runtime memory contains only:
 * - a bounded number of recently viewed players
 * - coalesced, not-yet-flushed sale deltas
 * <p>
 * The transaction ledger remains the authoritative per-sale audit trail.
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlSourceToSinkFlow"})
public final class SellPlayerHistoryStore {

    private static final long DEFAULT_CACHE_TTL_MILLIS =
            10L * 60L * 1000L;
    private static final int DEFAULT_CACHE_PLAYERS = 128;
    private static final int DEFAULT_MAX_PENDING_ENTRIES = 50_000;
    private static final long DEFAULT_FLUSH_MILLIS = 5_000L;
    private static final long WARNING_INTERVAL_NANOS =
            30_000_000_000L;

    private final Core core;
    private final ScheduledThreadPoolExecutor executor;

    private final LinkedHashMap<UUID, CacheEntry> cache =
            new LinkedHashMap<>(
                    32,
                    0.75F,
                    true
            );
    private final Map<HistoryKey, PendingDelta> pending =
            new HashMap<>();
    private final Map<HistoryKey, LegacyAbsolute> legacyPending =
            new HashMap<>();

    private final Map<UUID, List<Consumer<List<SellHistoryEntry>>>>
            pendingLoads =
            new HashMap<>();

    private volatile StoreConfig config;

    private boolean started;
    private boolean closed;
    private ScheduledFuture<?> pendingFlush;
    private long lastWarningNanos;
    private String initializedKey = "";

    public SellPlayerHistoryStore(
            Core core,
            FileConfiguration sellConfig
    ) {
        this.core = core;
        this.config = loadConfig(
                sellConfig
        );
        this.executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "Mineacle-SellHistory"
                                    );
                            thread.setDaemon(true);
                            return thread;
                        }
                );
        executor.setRemoveOnCancelPolicy(
                true
        );
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(
                false
        );
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(
                false
        );
    }

    public synchronized void start() {
        if (closed || started) {
            return;
        }

        started = true;
        scheduleInitialize();

        if (!pending.isEmpty()
                || !legacyPending.isEmpty()) {
            scheduleFlushLocked(
                    0L
            );
        }
    }

    public synchronized void reload(
            FileConfiguration sellConfig
    ) {
        config =
                loadConfig(
                        sellConfig
                );
        trimCache();

        if (started && !closed) {
            scheduleInitialize();
            scheduleFlushLocked(
                    0L
            );
        }
    }

    public synchronized void recordSale(
            UUID playerId,
            Material material,
            long amount,
            long payoutCents,
            long soldAt
    ) {
        if (closed
                || playerId == null
                || material == null
                || amount <= 0L
                || payoutCents < 0L) {
            return;
        }

        HistoryKey key =
                new HistoryKey(
                        playerId,
                        material
                );
        PendingDelta current =
                pending.get(key);

        if (current == null
                && pending.size()
                >= config.maxPendingEntries()) {
            warnRateLimited(
                    "Sell history pending aggregate limit reached — "
                            + "new aggregate keys are temporarily deferred; "
                            + "the transaction ledger still retains sale audit data",
                    null
            );
            updateCachedSale(
                    playerId,
                    material,
                    amount,
                    payoutCents,
                    soldAt
            );
            return;
        }

        if (current == null) {
            pending.put(
                    key,
                    new PendingDelta(
                            amount,
                            payoutCents,
                            Math.max(
                                    0L,
                                    soldAt
                            )
                    )
            );
        } else {
            pending.put(
                    key,
                    new PendingDelta(
                            safeAdd(
                                    current.amount(),
                                    amount
                            ),
                            safeAdd(
                                    current.totalCents(),
                                    payoutCents
                            ),
                            Math.max(
                                    current.lastSoldAt(),
                                    soldAt
                            )
                    )
            );
        }

        updateCachedSale(
                playerId,
                material,
                amount,
                payoutCents,
                soldAt
        );
        scheduleFlushLocked(
                config.flushMillis()
        );
    }

    /**
     * Preserves old absolute aggregate history without double-counting it on
     * repeated reloads. SQL applies GREATEST() rather than additive updates.
     */
    public synchronized void importLegacy(
            UUID playerId,
            Material material,
            long amount,
            long totalCents,
            long lastSoldAt
    ) {
        if (closed
                || playerId == null
                || material == null
                || amount <= 0L) {
            return;
        }

        HistoryKey key =
                new HistoryKey(
                        playerId,
                        material
                );
        LegacyAbsolute current =
                legacyPending.get(key);
        LegacyAbsolute next =
                new LegacyAbsolute(
                        Math.max(
                                amount,
                                current == null
                                        ? 0L
                                        : current.amount()
                        ),
                        Math.max(
                                totalCents,
                                current == null
                                        ? 0L
                                        : current.totalCents()
                        ),
                        Math.max(
                                lastSoldAt,
                                current == null
                                        ? 0L
                                        : current.lastSoldAt()
                        )
                );

        legacyPending.put(
                key,
                next
        );
        scheduleFlushLocked(
                config.flushMillis()
        );
    }

    public synchronized List<SellHistoryEntry> cached(
            UUID playerId
    ) {
        if (playerId == null) {
            return List.of();
        }

        purgeExpiredCache();

        CacheEntry entry =
                cache.get(playerId);

        if (entry == null) {
            return pendingOnly(
                    playerId
            );
        }

        return List.copyOf(
                entry.entries().values()
        );
    }

    public void loadAsync(
            UUID playerId,
            Consumer<List<SellHistoryEntry>> callback
    ) {
        if (playerId == null
                || callback == null) {
            return;
        }

        List<SellHistoryEntry> cached;
        boolean startLoad = false;

        synchronized (this) {
            purgeExpiredCache();

            CacheEntry entry =
                    cache.get(playerId);

            if (entry != null) {
                cached =
                        List.copyOf(
                                entry.entries()
                                        .values()
                        );
            } else {
                cached = null;
                List<Consumer<List<SellHistoryEntry>>> callbacks =
                        pendingLoads.get(playerId);

                if (callbacks == null) {
                    callbacks =
                            new ArrayList<>();
                    pendingLoads.put(
                            playerId,
                            callbacks
                    );
                    startLoad = true;
                }

                callbacks.add(callback);
            }
        }

        if (cached != null) {
            dispatch(
                    callback,
                    cached
            );
            return;
        }

        if (!startLoad) {
            return;
        }

        try {
            executor.execute(
                    () -> loadPlayer(
                            playerId
                    )
            );
        } catch (
                RejectedExecutionException ignored
        ) {
            List<Consumer<List<SellHistoryEntry>>> callbacks;

            synchronized (this) {
                callbacks =
                        pendingLoads.remove(
                                playerId
                        );
            }

            List<SellHistoryEntry> fallback =
                    cached(playerId);

            if (callbacks != null) {
                for (Consumer<List<SellHistoryEntry>> queued
                        : callbacks) {
                    dispatch(
                            queued,
                            fallback
                    );
                }
            }
        }
    }

    public synchronized void flushIfDirty() {
        if (closed
                || (pending.isEmpty()
                && legacyPending.isEmpty())) {
            return;
        }

        scheduleFlushLocked(
                0L
        );
    }

    public void shutdown() {
        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;

            if (pendingFlush != null) {
                pendingFlush.cancel(
                        false
                );
                pendingFlush = null;
            }
        }

        try {
            executor.execute(
                    this::flushNow
            );
        } catch (
                RejectedExecutionException ignored
        ) {
        }

        executor.shutdown();

        try {
            if (!executor.awaitTermination(
                    5L,
                    TimeUnit.SECONDS
            )) {
                executor.shutdownNow();
            }
        } catch (
                InterruptedException exception
        ) {
            Thread.currentThread()
                    .interrupt();
            executor.shutdownNow();
        }
    }

    private void scheduleInitialize() {
        try {
            executor.execute(
                    () -> {
                        StoreConfig snapshot =
                                config;

                        if (!snapshot.sqlConfigured()) {
                            return;
                        }

                        try {
                            initialize(
                                    snapshot
                            );
                        } catch (Exception exception) {
                            warnRateLimited(
                                    "Sell player history database unavailable",
                                    exception
                            );
                        }
                    }
            );
        } catch (
                RejectedExecutionException ignored
        ) {
        }
    }

    private void loadPlayer(
            UUID playerId
    ) {
        StoreConfig snapshot =
                config;
        Map<Material, SellHistoryEntry> loaded =
                new EnumMap<>(
                        Material.class
                );

        if (snapshot.sqlConfigured()) {
            try {
                initialize(
                        snapshot
                );

                try (Connection connection =
                             connection(snapshot);
                     PreparedStatement statement =
                             connection.prepareStatement("""
                                     SELECT material,
                                            amount,
                                            total_cents,
                                            last_sold_at
                                       FROM %s
                                      WHERE player_uuid = ?
                                     """.formatted(
                                     snapshot.table()
                             ))) {
                    statement.setQueryTimeout(
                            snapshot.queryTimeoutSeconds()
                    );
                    statement.setString(
                            1,
                            playerId.toString()
                    );

                    try (ResultSet result =
                                 statement.executeQuery()) {
                        while (result.next()) {
                            Material material =
                                    Material.matchMaterial(
                                            result.getString(
                                                    "material"
                                            )
                                    );

                            if (material == null) {
                                continue;
                            }

                            loaded.put(
                                    material,
                                    new SellHistoryEntry(
                                            material,
                                            Math.max(
                                                    0L,
                                                    result.getLong(
                                                            "amount"
                                                    )
                                            ),
                                            Math.max(
                                                    0L,
                                                    result.getLong(
                                                            "total_cents"
                                                    )
                                            ),
                                            Math.max(
                                                    0L,
                                                    result.getLong(
                                                            "last_sold_at"
                                                    )
                                            )
                                    )
                            );
                        }
                    }
                }
            } catch (Exception exception) {
                warnRateLimited(
                        "Could not load Sell history for a player",
                        exception
                );
            }
        }

        List<Consumer<List<SellHistoryEntry>>> callbacks;

        synchronized (this) {
            mergePendingInto(
                    playerId,
                    loaded
            );
            cache.put(
                    playerId,
                    new CacheEntry(
                            loaded,
                            System.currentTimeMillis()
                    )
            );
            trimCache();
            callbacks =
                    pendingLoads.remove(
                            playerId
                    );
        }

        if (callbacks == null
                || callbacks.isEmpty()) {
            return;
        }

        List<SellHistoryEntry> result =
                List.copyOf(
                        loaded.values()
                );

        for (Consumer<List<SellHistoryEntry>> callback
                : callbacks) {
            dispatch(
                    callback,
                    result
            );
        }
    }

    private void scheduleFlushLocked(
            long delayMillis
    ) {
        if (!started
                || closed
                || (pending.isEmpty()
                && legacyPending.isEmpty())) {
            return;
        }

        if (pendingFlush != null
                && !pendingFlush.isDone()) {
            return;
        }

        try {
            pendingFlush =
                    executor.schedule(
                            this::flushNow,
                            Math.max(
                                    0L,
                                    delayMillis
                            ),
                            TimeUnit.MILLISECONDS
                    );
        } catch (
                RejectedExecutionException ignored
        ) {
            pendingFlush = null;
        }
    }

    private void flushNow() {
        Map<HistoryKey, PendingDelta>
                deltaBatch;
        Map<HistoryKey, LegacyAbsolute>
                legacyBatch;
        StoreConfig snapshot =
                config;

        synchronized (this) {
            pendingFlush = null;

            if (pending.isEmpty()
                    && legacyPending.isEmpty()) {
                return;
            }

            deltaBatch =
                    new HashMap<>(
                            pending
                    );
            legacyBatch =
                    new HashMap<>(
                            legacyPending
                    );
            pending.clear();
            legacyPending.clear();
        }

        boolean saved =
                false;

        if (snapshot.sqlConfigured()) {
            try {
                initialize(snapshot);
                saveBatch(
                        snapshot,
                        deltaBatch,
                        legacyBatch
                );
                saved = true;
            } catch (Exception exception) {
                warnRateLimited(
                        "Could not flush Sell player history",
                        exception
                );
            }
        }

        if (!saved) {
            synchronized (this) {
                requeue(
                        deltaBatch,
                        legacyBatch
                );
                scheduleFlushLocked(
                        Math.max(
                                5_000L,
                                snapshot.flushMillis()
                        )
                );
            }
        }
    }

    private void saveBatch(
            StoreConfig configuration,
            Map<HistoryKey, PendingDelta> deltas,
            Map<HistoryKey, LegacyAbsolute> legacy
    ) throws Exception {
        try (Connection connection =
                     connection(configuration)) {
            connection.setAutoCommit(
                    false
            );

            try {
                if (!legacy.isEmpty()) {
                    try (PreparedStatement statement =
                                 connection.prepareStatement("""
                                         INSERT INTO %s (
                                             player_uuid,
                                             material,
                                             amount,
                                             total_cents,
                                             last_sold_at
                                         ) VALUES (?, ?, ?, ?, ?)
                                         ON DUPLICATE KEY UPDATE
                                             amount =
                                                 GREATEST(amount, VALUES(amount)),
                                             total_cents =
                                                 GREATEST(total_cents, VALUES(total_cents)),
                                             last_sold_at =
                                                 GREATEST(last_sold_at, VALUES(last_sold_at))
                                         """.formatted(
                                         configuration.table()
                                 ))) {
                        for (Map.Entry<HistoryKey, LegacyAbsolute> entry
                                : legacy.entrySet()) {
                            HistoryKey key =
                                    entry.getKey();
                            LegacyAbsolute value =
                                    entry.getValue();

                            statement.setString(
                                    1,
                                    key.playerId()
                                            .toString()
                            );
                            statement.setString(
                                    2,
                                    key.material()
                                            .name()
                            );
                            statement.setLong(
                                    3,
                                    value.amount()
                            );
                            statement.setLong(
                                    4,
                                    value.totalCents()
                            );
                            statement.setLong(
                                    5,
                                    value.lastSoldAt()
                            );
                            statement.addBatch();
                        }

                        statement.executeBatch();
                    }
                }

                if (!deltas.isEmpty()) {
                    try (PreparedStatement statement =
                                 connection.prepareStatement("""
                                         INSERT INTO %s (
                                             player_uuid,
                                             material,
                                             amount,
                                             total_cents,
                                             last_sold_at
                                         ) VALUES (?, ?, ?, ?, ?)
                                         ON DUPLICATE KEY UPDATE
                                             amount =
                                                 amount + VALUES(amount),
                                             total_cents =
                                                 total_cents + VALUES(total_cents),
                                             last_sold_at =
                                                 GREATEST(last_sold_at, VALUES(last_sold_at))
                                         """.formatted(
                                         configuration.table()
                                 ))) {
                        for (Map.Entry<HistoryKey, PendingDelta> entry
                                : deltas.entrySet()) {
                            HistoryKey key =
                                    entry.getKey();
                            PendingDelta value =
                                    entry.getValue();

                            statement.setString(
                                    1,
                                    key.playerId()
                                            .toString()
                            );
                            statement.setString(
                                    2,
                                    key.material()
                                            .name()
                            );
                            statement.setLong(
                                    3,
                                    value.amount()
                            );
                            statement.setLong(
                                    4,
                                    value.totalCents()
                            );
                            statement.setLong(
                                    5,
                                    value.lastSoldAt()
                            );
                            statement.addBatch();
                        }

                        statement.executeBatch();
                    }
                }

                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(
                        true
                );
            }
        }
    }

    private void initialize(
            StoreConfig configuration
    ) throws Exception {
        String key =
                configuration.jdbcUrl()
                        + "|"
                        + configuration.username()
                        + "|"
                        + configuration.table();

        synchronized (this) {
            if (initializedKey.equals(key)) {
                return;
            }
        }

        loadDriver(
                configuration
        );

        try (Connection connection =
                     connection(configuration);
             Statement statement =
                     connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid CHAR(36) NOT NULL,
                        material VARCHAR(64) NOT NULL,
                        amount BIGINT NOT NULL,
                        total_cents BIGINT NOT NULL,
                        last_sold_at BIGINT NOT NULL,
                        PRIMARY KEY (
                            player_uuid,
                            material
                        ),
                        INDEX idx_sell_history_last (
                            last_sold_at
                        )
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                    configuration.table()
            ));
        }

        synchronized (this) {
            initializedKey = key;
        }
    }

    private synchronized void updateCachedSale(
            UUID playerId,
            Material material,
            long amount,
            long payoutCents,
            long soldAt
    ) {
        CacheEntry cached =
                cache.get(playerId);

        if (cached == null) {
            return;
        }

        cached.entries().compute(
                material,
                (ignored, current) ->
                        new SellHistoryEntry(
                                material,
                                current == null
                                        ? amount
                                        : safeAdd(
                                                current.amount(),
                                                amount
                                        ),
                                current == null
                                        ? payoutCents
                                        : safeAdd(
                                                current.totalCents(),
                                                payoutCents
                                        ),
                                Math.max(
                                        soldAt,
                                        current == null
                                                ? 0L
                                                : current.lastSoldMillis()
                                )
                        )
        );
        cache.put(
                playerId,
                new CacheEntry(
                        cached.entries(),
                        System.currentTimeMillis()
                )
        );
    }

    private void mergePendingInto(
            UUID playerId,
            Map<Material, SellHistoryEntry> target
    ) {
        for (Map.Entry<HistoryKey, PendingDelta> entry
                : pending.entrySet()) {
            if (!entry.getKey()
                    .playerId()
                    .equals(playerId)) {
                continue;
            }

            Material material =
                    entry.getKey()
                            .material();
            PendingDelta delta =
                    entry.getValue();
            target.compute(
                    material,
                    (ignored, current) ->
                            new SellHistoryEntry(
                                    material,
                                    safeAdd(
                                            current == null
                                                    ? 0L
                                                    : current.amount(),
                                            delta.amount()
                                    ),
                                    safeAdd(
                                            current == null
                                                    ? 0L
                                                    : current.totalCents(),
                                            delta.totalCents()
                                    ),
                                    Math.max(
                                            current == null
                                                    ? 0L
                                                    : current.lastSoldMillis(),
                                            delta.lastSoldAt()
                                    )
                            )
            );
        }
    }

    private List<SellHistoryEntry> pendingOnly(
            UUID playerId
    ) {
        Map<Material, SellHistoryEntry> result =
                new EnumMap<>(
                        Material.class
                );
        mergePendingInto(
                playerId,
                result
        );
        return List.copyOf(
                result.values()
        );
    }

    private void requeue(
            Map<HistoryKey, PendingDelta> deltas,
            Map<HistoryKey, LegacyAbsolute> legacy
    ) {
        for (Map.Entry<HistoryKey, PendingDelta> entry
                : deltas.entrySet()) {
            PendingDelta current =
                    pending.get(
                            entry.getKey()
                    );
            PendingDelta value =
                    entry.getValue();

            if (current == null) {
                pending.put(
                        entry.getKey(),
                        value
                );
            } else {
                pending.put(
                        entry.getKey(),
                        new PendingDelta(
                                safeAdd(
                                        current.amount(),
                                        value.amount()
                                ),
                                safeAdd(
                                        current.totalCents(),
                                        value.totalCents()
                                ),
                                Math.max(
                                        current.lastSoldAt(),
                                        value.lastSoldAt()
                                )
                        )
                );
            }
        }

        for (Map.Entry<HistoryKey, LegacyAbsolute> entry
                : legacy.entrySet()) {
            LegacyAbsolute current =
                    legacyPending.get(
                            entry.getKey()
                    );
            LegacyAbsolute value =
                    entry.getValue();

            legacyPending.put(
                    entry.getKey(),
                    new LegacyAbsolute(
                            Math.max(
                                    current == null
                                            ? 0L
                                            : current.amount(),
                                    value.amount()
                            ),
                            Math.max(
                                    current == null
                                            ? 0L
                                            : current.totalCents(),
                                    value.totalCents()
                            ),
                            Math.max(
                                    current == null
                                            ? 0L
                                            : current.lastSoldAt(),
                                    value.lastSoldAt()
                            )
                    )
            );
        }
    }

    private synchronized void purgeExpiredCache() {
        long cutoff =
                System.currentTimeMillis()
                        - config.cacheTtlMillis();

        cache.entrySet()
                .removeIf(
                        entry ->
                                entry.getValue()
                                        .loadedAt()
                                < cutoff
                );
    }

    private synchronized void trimCache() {
        purgeExpiredCache();

        while (cache.size()
                > config.cachePlayers()) {
            UUID eldest =
                    cache.keySet()
                            .iterator()
                            .next();
            cache.remove(eldest);
        }
    }

    private void dispatch(
            Consumer<List<SellHistoryEntry>> callback,
            List<SellHistoryEntry> entries
    ) {
        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        () -> callback.accept(
                                entries
                        )
                );
    }

    private StoreConfig loadConfig(
            FileConfiguration sellConfig
    ) {
        String storage =
                nonBlank(
                        sellConfig.getString(
                                "market.storage",
                                "mysql"
                        ),
                        "mysql"
                );
        boolean sql =
                storage.equalsIgnoreCase("mysql")
                        || storage.equalsIgnoreCase("mariadb");

        File databaseFile =
                new File(
                        core.getDataFolder(),
                        nonBlank(
                                sellConfig.getString(
                                        "market.database-config-file",
                                        "webprofiles.yml"
                                ),
                                "webprofiles.yml"
                        )
                );
        FileConfiguration database =
                YamlConfiguration
                        .loadConfiguration(
                                databaseFile
                        );

        String prefix =
                safeIdentifier(
                        sellConfig.getString(
                                "market.table-prefix",
                                "mineacle_sell"
                        )
                );

        return new StoreConfig(
                sql,
                nonBlank(
                        database.getString(
                                "database.driver-class",
                                "com.mysql.cj.jdbc.Driver"
                        ),
                        "com.mysql.cj.jdbc.Driver"
                ),
                nonBlank(
                        database.getString(
                                "database.jdbc-url",
                                "jdbc:mysql://127.0.0.1:3306/mineacle"
                        ),
                        "jdbc:mysql://127.0.0.1:3306/mineacle"
                ),
                nonBlank(
                        database.getString(
                                "database.username",
                                "mineacle_core"
                        ),
                        "mineacle_core"
                ),
                database.getString(
                        "database.password",
                        ""
                ),
                prefix + "_player_history",
                Math.max(
                        16,
                        sellConfig.getInt(
                                "history.cache-players",
                                DEFAULT_CACHE_PLAYERS
                        )
                ),
                Math.max(
                        60_000L,
                        sellConfig.getLong(
                                "history.cache-ttl-minutes",
                                DEFAULT_CACHE_TTL_MILLIS / 60_000L
                        )
                                * 60L
                                * 1000L
                ),
                Math.max(
                        1_000,
                        sellConfig.getInt(
                                "history.max-pending-entries",
                                DEFAULT_MAX_PENDING_ENTRIES
                        )
                ),
                Math.max(
                        250L,
                        sellConfig.getLong(
                                "history.flush-seconds",
                                DEFAULT_FLUSH_MILLIS / 1000L
                        )
                                * 1000L
                ),
                Math.clamp(
                        sellConfig.getInt(
                                "history.query-timeout-seconds",
                                5
                        ),
                        1,
                        30
                )
        );
    }

    private Connection connection(
            StoreConfig configuration
    ) throws Exception {
        loadDriver(
                configuration
        );

        return DriverManager.getConnection(
                configuration.jdbcUrl(),
                configuration.username(),
                configuration.password()
        );
    }

    private void loadDriver(
            StoreConfig configuration
    ) throws ClassNotFoundException {
        if (!configuration
                .driverClass()
                .isBlank()) {
            Class.forName(
                    configuration.driverClass()
            );
        }
    }

    private String nonBlank(
            String value,
            String fallback
    ) {
        return value == null
                || value.isBlank()
                ? fallback
                : value.trim();
    }

    private String safeIdentifier(
            String configured
    ) {
        String fallback = "mineacle_sell";
        String value =
                configured == null
                        ? ""
                        : configured.trim();

        if (!value.matches(
                "[A-Za-z0-9_]{1,40}"
        )) {
            core.getLogger().warning(
                    "Invalid Sell table prefix '"
                            + configured
                            + "' — using "
                            + fallback
            );
            return fallback;
        }

        return value.toLowerCase(
                Locale.ROOT
        );
    }

    private long safeAdd(
            long first,
            long second
    ) {
        try {
            return Math.addExact(
                    first,
                    second
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void warnRateLimited(
            String message,
            Throwable throwable
    ) {
        long now =
                System.nanoTime();

        synchronized (this) {
            if (now - lastWarningNanos
                    < WARNING_INTERVAL_NANOS) {
                return;
            }

            lastWarningNanos = now;
        }

        if (throwable == null) {
            core.getLogger().warning(
                    message
            );
        } else {
            core.getLogger().log(
                    Level.WARNING,
                    message,
                    throwable
            );
        }
    }

    private record StoreConfig(
            boolean sqlConfigured,
            String driverClass,
            String jdbcUrl,
            String username,
            String password,
            String table,
            int cachePlayers,
            long cacheTtlMillis,
            int maxPendingEntries,
            long flushMillis,
            int queryTimeoutSeconds
    ) {
    }

    private record HistoryKey(
            UUID playerId,
            Material material
    ) {
    }

    private record PendingDelta(
            long amount,
            long totalCents,
            long lastSoldAt
    ) {
    }

    private record LegacyAbsolute(
            long amount,
            long totalCents,
            long lastSoldAt
    ) {
    }

    private record CacheEntry(
            Map<Material, SellHistoryEntry> entries,
            long loadedAt
    ) {
    }
}
