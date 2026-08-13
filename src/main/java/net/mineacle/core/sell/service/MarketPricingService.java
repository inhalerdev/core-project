package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import net.mineacle.core.sell.model.MarketDefinition;
import net.mineacle.core.sell.model.SellHistoryEntry;
import net.mineacle.core.sell.storage.CommodityMarketStorage;
import net.mineacle.core.sell.storage.SellPlayerHistoryStore;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.function.Consumer;

public final class MarketPricingService {

    public enum ResetStartResult {
        STARTED,
        ALREADY_RUNNING,
        STORAGE_UNAVAILABLE
    }

    public record ResetCompletion(
            boolean durable,
            boolean sqlCleared
    ) {
    }

    private static final long SQL_RETRY_MILLIS =
            5L * 60L * 1000L;
    private static final long SIX_HOURS =
            6L * 60L * 60L * 1000L;
    private static final long ONE_DAY =
            24L * 60L * 60L * 1000L;
    private static final long SEVEN_DAYS =
            7L * ONE_DAY;
    private static final long ROLLING_CACHE_MAX_AGE =
            30_000L;
    private static final long SHUTDOWN_FLUSH_BUDGET_MILLIS =
            5_000L;

    private final Core core;
    private final ScheduledThreadPoolExecutor persistenceExecutor;
    private final SellPlayerHistoryStore playerHistoryStore;
    private final long runtimeStartedAt =
            System.currentTimeMillis();

    private final Map<String, MarketState> states =
            new HashMap<>();
    private final Map<BucketKey, BucketTotals> buckets =
            new HashMap<>();
    private final Map<BucketKey, BucketTotals> resetPendingBuckets =
            new HashMap<>();
    private final Set<String> dirtyStates =
            new HashSet<>();
    private final Set<BucketKey> dirtyBuckets =
            new HashSet<>();
    private Map<Material, MarketDefinition> definitions =
            Map.of();
    private Map<Material, String> materialMarketKeys =
            Map.of();
    private Map<Material, Long> materialMarketUnits =
            Map.of();
    private Map<String, CommodityDefinition> commodities =
            Map.of();
    private Set<String> featuredExcluded =
            Set.of();
    private Set<String> featuredPool =
            Set.of();

    private CommodityMarketStorage commodityStorage;

    private boolean sqlConfigured;
    private boolean sqlReady;
    private boolean sqlConnecting;
    private boolean flushInFlight;
    private boolean resetInFlight;
    private boolean started;
    private boolean enabled;
    private long priceRevision;
    private boolean rollingDirty = true;

    private long bucketMillis;
    private long retentionMillis;
    private long refreshMillis;
    private long featuredRotationMillis;
    private long flushMillis;
    private long nextSqlRetryAt;
    private long lastFlushAt;
    private long lastRepriceAt;
    private long lastFeaturedRotationAt;
    private long minimumObservationMillis;
    private long storageGeneration;
    private long marketResetAt;
    private long pendingResetAt;
    private long rollingBuiltAt;
    private long yamlSnapshotMillis;
    private long lastYamlSnapshotAt;

    private double weightSixHours;
    private double weightTwentyFourHours;
    private double weightSevenDays;
    private double maximumChangeFraction;
    private double shortageFullEvidenceFraction;
    private double noSalesMaximumMultiplier;
    private int featuredItemCount;
    private int featuredFarmSlots;
    private double featuredMinimumBoost;
    private double featuredMaximumBoost;

    private Map<String, RollingSupply> rollingCache =
            Map.of();

    public MarketPricingService(
            Core core,
            FileConfiguration sellConfig,
            Map<Material, MarketDefinition> definitions,
            Map<Material, String> marketKeys,
            Map<Material, Long> marketUnits
    ) {
        this.core = core;
        this.persistenceExecutor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "Mineacle-SellMarket"
                                    );
                            thread.setDaemon(true);
                            return thread;
                        }
                );
        persistenceExecutor.setRemoveOnCancelPolicy(true);
        persistenceExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        persistenceExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        reloadSettings(
                sellConfig,
                definitions,
                marketKeys,
                marketUnits
        );

        try {
            commodityStorage.initializeYaml();
            mergeCommoditySnapshot(
                    commodityStorage.loadYaml(
                            System.currentTimeMillis()
                                    - retentionMillis
                    )
            );
        } catch (Exception exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not load sell-commodity-market.yml",
                    exception
            );
        }

        playerHistoryStore =
                new SellPlayerHistoryStore(
                        core,
                        sellConfig
                );

        ensureCommodityStates();
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        playerHistoryStore.start();
        attemptSqlConnection();
    }

    public synchronized void reload(
            FileConfiguration sellConfig,
            Map<Material, MarketDefinition> newDefinitions,
            Map<Material, String> newMarketKeys,
            Map<Material, Long> newMarketUnits
    ) {
        /*
         * Queue the previous storage generation before swapping configuration.
         * All physical Sell-market I/O is serialized by persistenceExecutor,
         * so reload never blocks the server thread and writes cannot overtake
         * one another. The new generation is marked dirty below and becomes
         * authoritative on its next flush.
         */
        flushAsync();

        reloadSettings(
                sellConfig,
                newDefinitions,
                newMarketKeys,
                newMarketUnits
        );
        playerHistoryStore.reload(
                sellConfig
        );
        ensureCommodityStates();
        markAllStatesDirty();

        /*
         * Configuration/catalog reloads can change market enablement or
         * multiplier bounds even when no MarketState number changes. Treat
         * that as a price-authority revision so global Worth snapshots never
         * retain a value built against the previous rules.
         */
        markPricesChanged();

        if (started) {
            attemptSqlConnection();
        }
    }

    public void tick() {
        long now =
                System.currentTimeMillis();

        synchronized (this) {
            if (!enabled
                    || resetInFlight) {
                return;
            }

            expireFeatured(now);

            if (now - lastRepriceAt
                    >= refreshMillis) {
                repriceNow(now, false);
            }

            if (now - lastFeaturedRotationAt
                    >= featuredRotationMillis) {
                rotateFeaturedNow(
                        now,
                        false
                );
            }

            pruneInMemory(
                    now - retentionMillis
            );

            if (now - lastFlushAt
                    >= flushMillis) {
                flushAsync();
            }

            if (sqlConfigured
                    && !sqlReady
                    && !sqlConnecting
                    && now >= nextSqlRetryAt) {
                attemptSqlConnection();
            }
        }
    }

    public synchronized double marketMultiplier(
            Material material
    ) {
        MarketState state =
                state(material);

        return state == null
                ? 1.0D
                : state.marketMultiplier;
    }

    public synchronized double featuredMultiplier(
            Material material
    ) {
        MarketState state =
                state(material);

        if (state == null
                || state.featuredUntil
                <= System.currentTimeMillis()) {
            return 1.0D;
        }

        return state.featuredMultiplier;
    }

    public synchronized double combinedMultiplier(
            Material material
    ) {
        CommodityDefinition commodity =
                commodity(material);

        if (commodity == null
                || !commodity.enabled()) {
            return 1.0D;
        }

        return roundMultiplier(
                clamp(
                        marketMultiplier(material)
                                * featuredMultiplier(material),
                        commodity.minimumMultiplier(),
                        commodity.maximumMultiplier()
                )
        );
    }

    public synchronized boolean isFeatured(
            Material material
    ) {
        MarketState state =
                state(material);

        return state != null
                && state.featuredMultiplier
                > 1.0001D
                && state.featuredUntil
                > System.currentTimeMillis();
    }

    public synchronized long featuredUntil(
            Material material
    ) {
        MarketState state =
                state(material);

        return state == null
                ? 0L
                : state.featuredUntil;
    }

    public synchronized long lastRepriceAt() {
        return lastRepriceAt;
    }

    public synchronized long refreshIntervalMillis() {
        return refreshMillis;
    }

    public synchronized long rollingUnits(
            Material material,
            long windowMillis
    ) {
        String key =
                marketKey(material);

        if (key == null) {
            return 0L;
        }

        long window =
                Math.max(
                        0L,
                        windowMillis
                );
        RollingSupply rolling =
                rollingSupply(
                        key,
                        System.currentTimeMillis()
                );

        if (window == SIX_HOURS) {
            return rolling.sixHoursUnits();
        }

        if (window == ONE_DAY) {
            return rolling.dayUnits();
        }

        if (window == SEVEN_DAYS) {
            return rolling.weekUnits();
        }

        long cutoff =
                System.currentTimeMillis()
                        - window;
        long total = 0L;

        for (Map.Entry<BucketKey, BucketTotals> entry
                : buckets.entrySet()) {
            if (!entry.getKey()
                    .marketKey()
                    .equals(key)
                    || entry.getKey()
                    .bucketStart()
                    < cutoff) {
                continue;
            }

            total =
                    safeAdd(
                            total,
                            entry.getValue()
                                    .unitsSold
                    );
        }

        return total;
    }

    public synchronized long rollingPayoutCents(
            Material material,
            long windowMillis
    ) {
        String key =
                marketKey(material);

        if (key == null) {
            return 0L;
        }

        long window =
                Math.max(
                        0L,
                        windowMillis
                );
        RollingSupply rolling =
                rollingSupply(
                        key,
                        System.currentTimeMillis()
                );

        if (window == SIX_HOURS) {
            return rolling
                    .sixHoursPayoutCents();
        }

        if (window == ONE_DAY) {
            return rolling
                    .dayPayoutCents();
        }

        if (window == SEVEN_DAYS) {
            return rolling
                    .weekPayoutCents();
        }

        long cutoff =
                System.currentTimeMillis()
                        - window;
        long total = 0L;

        for (Map.Entry<BucketKey, BucketTotals> entry
                : buckets.entrySet()) {
            if (!entry.getKey()
                    .marketKey()
                    .equals(key)
                    || entry.getKey()
                    .bucketStart()
                    < cutoff) {
                continue;
            }

            total =
                    safeAdd(
                            total,
                            entry.getValue()
                                    .payoutCents
                    );
        }

        return total;
    }

    public synchronized double supplyRatio(
            Material material
    ) {
        CommodityDefinition commodity =
                commodity(material);

        return supplyRatio(
                commodity,
                System.currentTimeMillis()
        );
    }

    public synchronized long targetUnits(
            Material material
    ) {
        CommodityDefinition commodity =
                commodity(material);

        return commodity == null
                ? 0L
                : commodity.targetUnitsPerDay();
    }

    public synchronized void recordSale(
            UUID playerId,
            Material material,
            long amount,
            long payoutCents,
            long soldAt
    ) {
        if (playerId == null
                || material == null
                || amount <= 0L
                || payoutCents < 0L) {
            return;
        }

        /*
         * Player history is independent from dynamic commodity pricing. Fixed
         * and safety-floor sales still belong in the player's aggregate and in
         * the authoritative transaction ledger, but they do not need rolling
         * 6h/24h/7d commodity buckets that can never influence their price.
         */
        playerHistoryStore.recordSale(
                playerId,
                material,
                amount,
                payoutCents,
                soldAt
        );

        CommodityDefinition commodity =
                commodity(material);

        if (commodity == null
                || !commodity.enabled()) {
            return;
        }

        String key =
                commodity.key();
        long unitsPerItem =
                marketUnits(material);
        long normalizedUnits =
                safeMultiply(
                        amount,
                        unitsPerItem
                );

        if (normalizedUnits <= 0L) {
            return;
        }

        long resetCutoff =
                resetInFlight
                        ? pendingResetAt
                        : marketResetAt;
        BucketKey bucketKey =
                new BucketKey(
                        key,
                        bucketStart(
                                soldAt,
                                resetCutoff
                        )
                );

        if (resetInFlight) {
            BucketTotals pending =
                    resetPendingBuckets.computeIfAbsent(
                            bucketKey,
                            ignored ->
                                    new BucketTotals()
                    );

            pending.unitsSold =
                    safeAdd(
                            pending.unitsSold,
                            normalizedUnits
                    );
            pending.payoutCents =
                    safeAdd(
                            pending.payoutCents,
                            payoutCents
                    );
            return;
        }

        BucketTotals bucket =
                buckets.computeIfAbsent(
                        bucketKey,
                        ignored ->
                                new BucketTotals()
                );

        bucket.unitsSold =
                safeAdd(
                        bucket.unitsSold,
                        normalizedUnits
                );
        bucket.payoutCents =
                safeAdd(
                        bucket.payoutCents,
                        payoutCents
                );
        dirtyBuckets.add(bucketKey);
        rollingDirty = true;
    }

    public synchronized List<SellHistoryEntry> history(
            UUID playerId
    ) {
        return playerHistoryStore
                .cached(playerId);
    }

    public void loadHistoryAsync(
            UUID playerId,
            Consumer<List<SellHistoryEntry>> callback
    ) {
        playerHistoryStore.loadAsync(
                playerId,
                callback
        );
    }

    public synchronized void forceReprice() {
        long now =
                System.currentTimeMillis();
        repriceNow(now, true);
        flushAsync();
    }

    public synchronized void forceFeaturedRotation() {
        long now =
                System.currentTimeMillis();
        rotateFeaturedNow(
                now,
                true
        );
        flushAsync();
    }

    public synchronized ResetStartResult reset(
            Consumer<ResetCompletion> completion
    ) {
        if (resetInFlight) {
            return ResetStartResult.ALREADY_RUNNING;
        }

        CommodityMarketStorage storage =
                commodityStorage;

        if (storage == null
                || persistenceExecutor.isShutdown()) {
            core.getLogger().warning(
                    "Sell market reset could not start — "
                            + "commodity storage is unavailable"
            );
            return ResetStartResult.STORAGE_UNAVAILABLE;
        }

        resetInFlight = true;
        resetPendingBuckets.clear();

        /*
         * The reset is submitted to the same single persistence executor used
         * by every market flush. Any older write therefore completes before
         * the durable tombstone and no later stale write can overtake it.
         */
        storageGeneration++;
        long generation =
                storageGeneration;
        long resetAt =
                System.currentTimeMillis();
        pendingResetAt =
                resetAt;

        try {
            persistenceExecutor.execute(
                    () -> {
                        CommodityMarketStorage.ResetResult result =
                                null;
                        Exception failure =
                                null;

                        try {
                            result =
                                    storage.resetPersistent(
                                            resetAt
                                    );
                        } catch (Exception exception) {
                            failure = exception;
                        }

                        CommodityMarketStorage.ResetResult finalResult =
                                result;
                        Exception finalFailure =
                                failure;

                        dispatchMain(
                                () -> finishPersistentReset(
                                        generation,
                                        storage,
                                        resetAt,
                                        finalResult,
                                        finalFailure,
                                        completion
                                )
                        );
                    }
            );
        } catch (RejectedExecutionException exception) {
            resetInFlight = false;
            pendingResetAt = 0L;
            return ResetStartResult.STORAGE_UNAVAILABLE;
        }

        return ResetStartResult.STARTED;
    }

    public synchronized boolean resetInFlight() {
        return resetInFlight;
    }

    public synchronized void importHistory(
            UUID playerId,
            Material material,
            long amount,
            long totalCents,
            long lastSoldAt
    ) {
        playerHistoryStore.importLegacy(
                playerId,
                material,
                amount,
                Math.max(0L, totalCents),
                Math.max(0L, lastSoldAt)
        );
    }

    public synchronized String tier(
            Material material
    ) {
        double multiplier =
                combinedMultiplier(
                        material
                );

        if (isFeatured(material)
                && multiplier >= 1.20D) {
            return "featured";
        }

        if (multiplier >= 1.35D) {
            return "shortage";
        }

        if (multiplier >= 1.10D) {
            return "high_demand";
        }

        if (multiplier <= 0.55D) {
            return "saturated";
        }

        if (multiplier <= 0.85D) {
            return "oversupplied";
        }

        return "normal";
    }

    public synchronized long priceRevision() {
        return priceRevision;
    }

    public synchronized void flushIfDirty() {
        playerHistoryStore.flushIfDirty();
        flushAsync();
    }

    public void shutdown() {
        CommodityMarketStorage storage;
        CommodityMarketStorage.SaveBatch finalBatch;
        boolean attemptSql;

        synchronized (this) {
            if (!started
                    && persistenceExecutor.isShutdown()) {
                return;
            }

            started = false;
            storage = commodityStorage;
            attemptSql =
                    sqlReady
                            && storage != null
                            && storage.sqlConfigured();

            /*
             * If reset I/O is already queued, its durable tombstone executes
             * before this final task. Persist only post-reset sale buckets in
             * that case; writing the pre-reset full state again is unnecessary.
             */
            finalBatch =
                    resetInFlight
                            ? createResetPendingBatch()
                            : createFullCommodityBatch();
        }

        if (storage != null
                && !finalBatch.empty()) {
            try {
                persistenceExecutor.execute(
                        () -> persistFinalBatch(
                                storage,
                                finalBatch,
                                attemptSql
                        )
                );
            } catch (RejectedExecutionException exception) {
                core.getLogger().log(
                        Level.SEVERE,
                        "Sell market final persistence task was rejected",
                        exception
                );
            }
        }

        persistenceExecutor.shutdown();

        try {
            if (!persistenceExecutor.awaitTermination(
                    SHUTDOWN_FLUSH_BUDGET_MILLIS,
                    TimeUnit.MILLISECONDS
            )) {
                persistenceExecutor.shutdownNow();
                core.getLogger().severe(
                        "Sell market persistence shutdown budget expired"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            persistenceExecutor.shutdownNow();
        }

        playerHistoryStore.shutdown();

        synchronized (this) {
            sqlReady = false;
            sqlConnecting = false;
            flushInFlight = false;
        }
    }

    private void reloadSettings(
            FileConfiguration config,
            Map<Material, MarketDefinition> newDefinitions,
            Map<Material, String> newMarketKeys,
            Map<Material, Long> newMarketUnits
    ) {
        definitions =
                Map.copyOf(
                        newDefinitions
                );

        Map<Material, String> keys =
                new EnumMap<>(
                        Material.class
                );
        Map<Material, Long> units =
                new EnumMap<>(
                        Material.class
                );

        for (Material material
                : newDefinitions.keySet()) {
            String key =
                    newMarketKeys
                            .get(material);

            if (key == null
                    || key.isBlank()) {
                key = material.name();
            }

            keys.put(
                    material,
                    normalizeKey(key)
            );
            units.put(
                    material,
                    Math.max(
                            1L,
                            newMarketUnits
                                    .getOrDefault(
                                            material,
                                            1L
                                    )
                    )
            );
        }

        materialMarketKeys =
                Map.copyOf(keys);
        materialMarketUnits =
                Map.copyOf(units);
        commodities =
                buildCommodities();

        enabled =
                config.getBoolean(
                        "market.enabled",
                        true
                );
        bucketMillis =
                Math.max(
                        5L,
                        config.getLong(
                                "market.bucket-minutes",
                                60L
                        )
                )
                        * 60L
                        * 1000L;
        retentionMillis =
                Math.max(
                        1L,
                        config.getLong(
                                "market.retention-days",
                                7L
                        )
                )
                        * ONE_DAY;
        refreshMillis =
                Math.max(
                        1L,
                        config.getLong(
                                "market.reprice-interval-minutes",
                                15L
                        )
                )
                        * 60L
                        * 1000L;
        featuredRotationMillis =
                Math.max(
                        1L,
                        config.getLong(
                                "market.featured.rotation-hours",
                                12L
                        )
                )
                        * 60L
                        * 60L
                        * 1000L;
        flushMillis =
                Math.max(
                        5L,
                        config.getLong(
                                "market.flush-seconds",
                                30L
                        )
                )
                        * 1000L;
        maximumChangeFraction =
                clamp(
                        config.getDouble(
                                "market.maximum-change-per-refresh-percent",
                                8.0D
                        )
                                / 100.0D,
                        0.001D,
                        1.0D
                );
        minimumObservationMillis =
                Math.max(
                        0L,
                        config.getLong(
                                "market.minimum-observation-hours",
                                6L
                        )
                )
                        * 60L
                        * 60L
                        * 1000L;
        shortageFullEvidenceFraction =
                clamp(
                        config.getDouble(
                                "market.confidence.shortage-full-evidence-fraction",
                                0.35D
                        ),
                        0.01D,
                        1.0D
                );
        noSalesMaximumMultiplier =
                clamp(
                        config.getDouble(
                                "market.confidence.no-sales-maximum-multiplier",
                                1.10D
                        ),
                        1.0D,
                        2.0D
                );
        yamlSnapshotMillis =
                Math.max(
                        1L,
                        config.getLong(
                                "market.recovery-snapshot-minutes",
                                30L
                        )
                )
                        * 60L
                        * 1000L;
        featuredItemCount =
                Math.max(
                        0,
                        config.getInt(
                                "market.featured.active-items",
                                8
                        )
                );
        featuredFarmSlots =
                Math.clamp(
                        config.getInt(
                                "market.featured.minimum-farm-slots",
                                5
                        ),
                        0,
                        featuredItemCount
                );
        featuredMinimumBoost =
                clamp(
                        config.getDouble(
                                "market.featured.minimum-multiplier",
                                1.10D
                        ),
                        1.0D,
                        10.0D
                );
        featuredMaximumBoost =
                clamp(
                        config.getDouble(
                                "market.featured.maximum-multiplier",
                                1.35D
                        ),
                        featuredMinimumBoost,
                        10.0D
                );

        weightSixHours =
                Math.max(
                        0.0D,
                        config.getDouble(
                                "market.weights.last-6-hours",
                                0.60D
                        )
                );
        weightTwentyFourHours =
                Math.max(
                        0.0D,
                        config.getDouble(
                                "market.weights.last-24-hours",
                                0.30D
                        )
                );
        weightSevenDays =
                Math.max(
                        0.0D,
                        config.getDouble(
                                "market.weights.last-7-days",
                                0.10D
                        )
                );
        normalizeWeights();

        Set<String> excluded =
                new HashSet<>();

        for (String raw
                : config.getStringList(
                "market.featured.excluded-items"
        )) {
            Material material =
                    Material.matchMaterial(
                            raw
                    );

            if (material != null) {
                String key =
                        marketKey(material);

                if (key != null) {
                    excluded.add(key);
                }
            } else if (!raw.isBlank()) {
                excluded.add(
                        normalizeKey(raw)
                );
            }
        }

        featuredExcluded =
                Set.copyOf(excluded);

        Set<String> pool =
                new HashSet<>();

        for (String raw
                : config.getStringList(
                "market.featured.pool"
        )) {
            Material material =
                    Material.matchMaterial(
                            raw
                    );

            if (material != null) {
                String key =
                        marketKey(material);

                if (key != null) {
                    pool.add(key);
                }
            } else if (!raw.isBlank()) {
                pool.add(
                        normalizeKey(raw)
                );
            }
        }

        featuredPool =
                Set.copyOf(pool);
        rollingDirty = true;

        storageGeneration++;
        commodityStorage =
                new CommodityMarketStorage(
                        core,
                        config
                );
        marketResetAt =
                commodityStorage.resetAt();

        sqlConfigured =
                commodityStorage.sqlConfigured();
        sqlReady = false;
        sqlConnecting = false;
        nextSqlRetryAt = 0L;
    }

    private Map<String, CommodityDefinition>
    buildCommodities() {
        Map<String, CommodityAccumulator>
                accumulators =
                new HashMap<>();

        for (MarketDefinition definition
                : definitions.values()) {
            Material material =
                    definition.material();
            String key =
                    materialMarketKeys
                            .getOrDefault(
                                    material,
                                    material.name()
                            );
            long units =
                    Math.max(
                            1L,
                            materialMarketUnits
                                    .getOrDefault(
                                            material,
                                            1L
                                    )
                    );

            accumulators
                    .computeIfAbsent(
                            key,
                            CommodityAccumulator
                                    ::new
                    )
                    .add(
                            definition,
                            units
                    );
        }

        Map<String, CommodityDefinition>
                result =
                new HashMap<>();

        for (CommodityAccumulator accumulator
                : accumulators.values()) {
            result.put(
                    accumulator.key,
                    accumulator.build()
            );
        }

        return Map.copyOf(result);
    }



    private void attemptSqlConnection() {
        if (!started
                || !sqlConfigured
                || sqlReady
                || sqlConnecting
                || commodityStorage == null
                || persistenceExecutor.isShutdown()) {
            return;
        }

        sqlConnecting = true;

        long generation =
                storageGeneration;
        long bucketsSince =
                System.currentTimeMillis()
                        - retentionMillis;
        CommodityMarketStorage commodity =
                commodityStorage;

        try {
            persistenceExecutor.execute(
                    () -> {
                        CommodityMarketStorage.Snapshot snapshot =
                                null;
                        Exception failure = null;

                        try {
                            commodity.initializeSql();
                            snapshot =
                                    commodity.loadSql(
                                            bucketsSince
                                    );
                        } catch (Exception exception) {
                            failure = exception;
                        }

                        CommodityMarketStorage.Snapshot loaded =
                                snapshot;
                        Exception error = failure;

                        dispatchMain(
                                () -> finishSqlConnection(
                                        generation,
                                        commodity,
                                        loaded,
                                        error
                                )
                        );
                    }
            );
        } catch (RejectedExecutionException exception) {
            sqlConnecting = false;
        }
    }

    private synchronized void finishSqlConnection(
            long generation,
            CommodityMarketStorage commodity,
            CommodityMarketStorage.Snapshot snapshot,
            Exception failure
    ) {
        if (generation != storageGeneration
                || commodity != commodityStorage) {
            return;
        }

        sqlConnecting = false;

        if (!started) {
            return;
        }

        if (failure != null
                || snapshot == null) {
            sqlReady = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + SQL_RETRY_MILLIS;

            core.getLogger().log(
                    Level.WARNING,
                    "Sell commodity market database unavailable — "
                            + "using YAML recovery snapshot and retrying later",
                    failure
            );
            return;
        }

        mergeCommoditySnapshot(snapshot);
        sqlReady = true;
        nextSqlRetryAt = 0L;
        markEverythingDirty();

        core.getLogger().info(
                "Sell commodity market database connected"
        );
        flushAsync();
    }

    private void repriceNow(
            long now,
            boolean immediate
    ) {
        if (!enabled) {
            return;
        }

        ensureCommodityStates();
        rebuildRollingCacheIfNeeded(
                now
        );

        for (CommodityDefinition commodity
                : commodities.values()) {
            if (!commodity.enabled()) {
                continue;
            }

            MarketState state =
                    states.get(
                            commodity.key()
                    );
            double ratio =
                    supplyRatio(
                            commodity,
                            now
                    );
            double desired =
                    desiredMultiplier(
                            ratio,
                            commodity.minimumMultiplier(),
                            commodity.maximumMultiplier()
                    );
            double confidence =
                    observationConfidence(
                            commodity.key(),
                            now
                    );

            if (ratio < 1.0D
                    && desired > 1.0D) {
                RollingSupply rolling =
                        rollingSupply(
                                commodity.key(),
                                now
                        );
                double weightedDaily =
                        (rolling.sixHoursUnits()
                                * 4.0D
                                * weightSixHours)
                                + (rolling.dayUnits()
                                * weightTwentyFourHours)
                                + ((rolling.weekUnits()
                                / 7.0D)
                                * weightSevenDays);
                double fullEvidenceUnits =
                        Math.max(
                                1.0D,
                                commodity.targetUnitsPerDay()
                                        * shortageFullEvidenceFraction
                        );
                double volumeEvidence =
                        clamp(
                                weightedDaily
                                        / fullEvidenceUnits,
                                0.0D,
                                1.0D
                        );
                double scarcityCeiling =
                        interpolate(
                                Math.min(
                                        commodity.maximumMultiplier(),
                                        noSalesMaximumMultiplier
                                ),
                                commodity.maximumMultiplier(),
                                volumeEvidence
                        );

                desired =
                        Math.min(
                                desired,
                                scarcityCeiling
                        );
            }

            desired =
                    interpolate(
                            1.0D,
                            desired,
                            confidence
                    );

            double next =
                    immediate
                            ? desired
                            : smooth(
                                    state.marketMultiplier,
                                    desired
                            );

            next =
                    clamp(
                            next,
                            commodity.minimumMultiplier(),
                            commodity.maximumMultiplier()
                    );

            double rounded =
                    roundMultiplier(next);

            if (Math.abs(
                    state.marketMultiplier
                            - rounded
            ) >= 0.0001D) {
                markPricesChanged();
            }

            state.marketMultiplier =
                    rounded;
            state.targetUnitsPerDay =
                    commodity.targetUnitsPerDay();
            state.lastRepricedAt =
                    now;
            dirtyStates.add(
                    commodity.key()
            );
        }

        lastRepriceAt = now;
    }

    private void rotateFeaturedNow(
            long now,
            boolean immediate
    ) {
        if (!enabled
                || featuredItemCount <= 0) {
            clearFeatured();
            lastFeaturedRotationAt = now;
            return;
        }

        clearFeatured();
        rebuildRollingCacheIfNeeded(now);

        List<String> candidates =
                commodities.values()
                        .stream()
                        .filter(CommodityDefinition::enabled)
                        .map(CommodityDefinition::key)
                        .filter(key ->
                                !featuredExcluded.contains(key)
                        )
                        .filter(key ->
                                featuredPool.isEmpty()
                                        || featuredPool.contains(key)
                        )
                        .sorted(
                                Comparator
                                        .comparingDouble(
                                                this::supplyRatioByKey
                                        )
                                        .thenComparingInt(key ->
                                                deterministicOrder(
                                                        key,
                                                        now
                                                )
                                        )
                        )
                        .toList();

        List<String> selected =
                new ArrayList<>();

        if (featuredFarmSlots > 0) {
            List<String> farmCandidates =
                    candidates.stream()
                            .filter(key -> {
                                CommodityDefinition commodity =
                                        commodities.get(key);
                                return commodity != null
                                        && commodity.farmMeta();
                            })
                            .toList();

            selectFeaturedCandidates(
                    farmCandidates,
                    featuredFarmSlots,
                    now,
                    selected
            );
        }

        selectFeaturedCandidates(
                candidates,
                featuredItemCount - selected.size(),
                now,
                selected
        );

        long until =
                now + featuredRotationMillis;

        for (String key : selected) {
            double ratio =
                    Math.min(
                            1.0D,
                            supplyRatioByKey(key)
                    );
            double shortage =
                    1.0D - ratio;
            double boost =
                    featuredMinimumBoost
                            + ((featuredMaximumBoost
                            - featuredMinimumBoost)
                            * shortage);

            MarketState state =
                    states.get(key);
            double rounded =
                    roundMultiplier(boost);

            if (Math.abs(
                    state.featuredMultiplier
                            - rounded
            ) >= 0.0001D
                    || state.featuredUntil
                    != until) {
                markPricesChanged();
            }

            state.featuredMultiplier =
                    rounded;
            state.featuredUntil =
                    until;
            dirtyStates.add(key);
        }

        lastFeaturedRotationAt = now;

        if (immediate) {
            repriceNow(
                    now,
                    true
            );
        }
    }

    private void selectFeaturedCandidates(
            List<String> orderedCandidates,
            int requested,
            long now,
            List<String> selected
    ) {
        if (requested <= 0
                || orderedCandidates.isEmpty()) {
            return;
        }

        List<String> remaining =
                orderedCandidates.stream()
                        .filter(key ->
                                !selected.contains(key)
                        )
                        .toList();

        if (remaining.isEmpty()) {
            return;
        }

        int poolLimit =
                Math.min(
                        remaining.size(),
                        requested * 3
                );
        List<String> shortagePool =
                new ArrayList<>(
                        remaining.subList(
                                0,
                                poolLimit
                        )
                );

        shortagePool.sort(
                Comparator.comparingInt(key ->
                        deterministicOrder(
                                key,
                                now
                        )
                )
        );

        for (String key : shortagePool) {
            if (requested <= 0) {
                break;
            }

            selected.add(key);
            requested--;
        }
    }

    private void clearFeatured() {
        for (Map.Entry<String, MarketState>
                entry : states.entrySet()) {
            MarketState state =
                    entry.getValue();

            if (state.featuredMultiplier
                    != 1.0D
                    || state.featuredUntil
                    != 0L) {
                state.featuredMultiplier =
                        1.0D;
                state.featuredUntil =
                        0L;
                dirtyStates.add(
                        entry.getKey()
                );
                markPricesChanged();
            }
        }
    }

    private void expireFeatured(
            long now
    ) {
        for (Map.Entry<String, MarketState>
                entry : states.entrySet()) {
            MarketState state =
                    entry.getValue();

            if (state.featuredUntil > 0L
                    && state.featuredUntil
                    <= now) {
                state.featuredUntil =
                        0L;
                state.featuredMultiplier =
                        1.0D;
                dirtyStates.add(
                        entry.getKey()
                );
                markPricesChanged();
            }
        }
    }

    private double desiredMultiplier(
            double ratio,
            double minimum,
            double maximum
    ) {
        if (ratio <= 0.25D) {
            return maximum;
        }

        if (ratio <= 0.75D) {
            return interpolate(
                    maximum,
                    Math.min(
                            maximum,
                            1.15D
                    ),
                    (ratio - 0.25D)
                            / 0.50D
            );
        }

        if (ratio <= 1.25D) {
            return interpolate(
                    Math.min(
                            maximum,
                            1.15D
                    ),
                    Math.max(
                            minimum,
                            0.90D
                    ),
                    (ratio - 0.75D)
                            / 0.50D
            );
        }

        if (ratio <= 2.0D) {
            return interpolate(
                    Math.max(
                            minimum,
                            0.90D
                    ),
                    minimum,
                    (ratio - 1.25D)
                            / 0.75D
            );
        }

        return minimum;
    }

    private double smooth(
            double current,
            double desired
    ) {
        if (current <= 0.0D) {
            return desired;
        }

        double maximumMove =
                Math.max(
                        0.01D,
                        current
                                * maximumChangeFraction
                );

        if (desired > current) {
            return Math.min(
                    desired,
                    current + maximumMove
            );
        }

        return Math.max(
                desired,
                current - maximumMove
        );
    }

    private double observationConfidence(
            String key,
            long now
    ) {
        if (minimumObservationMillis
                <= 0L) {
            return 1.0D;
        }

        long oldest =
                Long.MAX_VALUE;

        for (BucketKey bucket
                : buckets.keySet()) {
            if (bucket.marketKey()
                    .equals(key)) {
                oldest =
                        Math.min(
                                oldest,
                                bucket.bucketStart()
                        );
            }
        }

        long observed =
                oldest == Long.MAX_VALUE
                        ? Math.max(
                                0L,
                                now - runtimeStartedAt
                        )
                        : Math.max(
                                0L,
                                now - oldest
                                        + bucketMillis
                        );

        return clamp(
                observed
                        / (double)
                        minimumObservationMillis,
                0.0D,
                1.0D
        );
    }

    private double supplyRatio(
            CommodityDefinition commodity,
            long now
    ) {
        if (commodity == null
                || commodity.targetUnitsPerDay()
                <= 0L) {
            return 1.0D;
        }

        RollingSupply rolling =
                rollingSupply(
                        commodity.key(),
                        now
                );
        double weightedDaily =
                (rolling.sixHoursUnits()
                        * 4.0D
                        * weightSixHours)
                        + (rolling.dayUnits()
                        * weightTwentyFourHours)
                        + ((rolling.weekUnits()
                        / 7.0D)
                        * weightSevenDays);

        return Math.max(
                0.0D,
                weightedDaily
                        / commodity.targetUnitsPerDay()
        );
    }

    private double supplyRatioByKey(
            String key
    ) {
        return supplyRatio(
                commodities.get(key),
                System.currentTimeMillis()
        );
    }

    private void rebuildRollingCacheIfNeeded(
            long now
    ) {
        if (!rollingDirty
                && now - rollingBuiltAt
                < ROLLING_CACHE_MAX_AGE) {
            return;
        }

        long sixCutoff =
                now - SIX_HOURS;
        long dayCutoff =
                now - ONE_DAY;
        long weekCutoff =
                now - SEVEN_DAYS;

        Map<String, MutableRollingSupply>
                mutable =
                new HashMap<>();

        for (Map.Entry<BucketKey, BucketTotals>
                entry : buckets.entrySet()) {
            long start =
                    entry.getKey()
                            .bucketStart();

            if (start < weekCutoff) {
                continue;
            }

            MutableRollingSupply supply =
                    mutable.computeIfAbsent(
                            entry.getKey()
                                    .marketKey(),
                            ignored ->
                                    new MutableRollingSupply()
                    );
            BucketTotals totals =
                    entry.getValue();

            supply.weekUnits =
                    safeAdd(
                            supply.weekUnits,
                            totals.unitsSold
                    );
            supply.weekPayoutCents =
                    safeAdd(
                            supply.weekPayoutCents,
                            totals.payoutCents
                    );

            if (start >= dayCutoff) {
                supply.dayUnits =
                        safeAdd(
                                supply.dayUnits,
                                totals.unitsSold
                        );
                supply.dayPayoutCents =
                        safeAdd(
                                supply.dayPayoutCents,
                                totals.payoutCents
                        );
            }

            if (start >= sixCutoff) {
                supply.sixHoursUnits =
                        safeAdd(
                                supply.sixHoursUnits,
                                totals.unitsSold
                        );
                supply.sixHoursPayoutCents =
                        safeAdd(
                                supply.sixHoursPayoutCents,
                                totals.payoutCents
                        );
            }
        }

        Map<String, RollingSupply> built =
                new HashMap<>();

        for (Map.Entry<String, MutableRollingSupply>
                entry : mutable.entrySet()) {
            built.put(
                    entry.getKey(),
                    entry.getValue()
                            .snapshot()
            );
        }

        rollingCache =
                Map.copyOf(built);
        rollingBuiltAt = now;
        rollingDirty = false;
    }

    private RollingSupply rollingSupply(
            String key,
            long now
    ) {
        rebuildRollingCacheIfNeeded(
                now
        );

        return rollingCache
                .getOrDefault(
                        key,
                        RollingSupply.ZERO
                );
    }

    private void ensureCommodityStates() {
        states.keySet()
                .removeIf(
                        key ->
                                !commodities
                                .containsKey(key)
                );

        for (CommodityDefinition commodity
                : commodities.values()) {
            MarketState state =
                    states.computeIfAbsent(
                            commodity.key(),
                            ignored ->
                                    new MarketState()
                    );

            if (!commodity.enabled()
                    && state.marketMultiplier
                    != 1.0D) {
                state.marketMultiplier =
                        1.0D;
                markPricesChanged();
                dirtyStates.add(
                        commodity.key()
                );
            }

            state.targetUnitsPerDay =
                    commodity.targetUnitsPerDay();
        }
    }

    private MarketState state(
            Material material
    ) {
        CommodityDefinition commodity =
                commodity(material);

        if (commodity == null) {
            return null;
        }

        MarketState state =
                states.computeIfAbsent(
                        commodity.key(),
                        ignored ->
                                new MarketState()
                );
        state.targetUnitsPerDay =
                commodity.targetUnitsPerDay();

        return state;
    }

    private CommodityDefinition commodity(
            Material material
    ) {
        String key =
                marketKey(material);

        return key == null
                ? null
                : commodities.get(key);
    }

    private String marketKey(
            Material material
    ) {
        if (material == null) {
            return null;
        }

        String key =
                materialMarketKeys
                        .get(material);

        if (key == null
                || key.isBlank()) {
            return material.name();
        }

        return key;
    }

    private long marketUnits(
            Material material
    ) {
        if (material == null) {
            return 1L;
        }

        return Math.max(
                1L,
                materialMarketUnits
                        .getOrDefault(
                                material,
                                1L
                        )
        );
    }

    private void mergeCommoditySnapshot(
            CommodityMarketStorage.Snapshot
                    snapshot
    ) {
        if (snapshot == null) {
            return;
        }

        for (CommodityMarketStorage.MarketStateData
                data : snapshot.states()) {
            String key =
                    normalizeKey(
                            data.marketKey()
                    );

            if (!commodities.containsKey(
                    key
            )) {
                continue;
            }

            MarketState current =
                    states.get(key);

            if (current == null
                    || data.lastRepricedAt()
                    >= current.lastRepricedAt) {
                MarketState merged =
                        new MarketState();
                merged.marketMultiplier =
                        safeMultiplier(
                                data.marketMultiplier()
                        );
                merged.featuredMultiplier =
                        safeMultiplier(
                                data.featuredMultiplier()
                        );
                merged.featuredUntil =
                        Math.max(
                                0L,
                                data.featuredUntil()
                        );
                merged.lastRepricedAt =
                        Math.max(
                                0L,
                                data.lastRepricedAt()
                        );
                merged.targetUnitsPerDay =
                        Math.max(
                                1L,
                                data.targetUnitsPerDay()
                        );
                states.put(
                        key,
                        merged
                );

                lastRepriceAt =
                        Math.max(
                                lastRepriceAt,
                                merged.lastRepricedAt
                        );

                if (merged.featuredUntil
                        > 0L) {
                    lastFeaturedRotationAt =
                            Math.max(
                                    lastFeaturedRotationAt,
                                    merged.featuredUntil
                                            - featuredRotationMillis
                            );
                }
            }
        }

        for (CommodityMarketStorage.BucketData
                data : snapshot.buckets()) {
            String key =
                    normalizeKey(
                            data.marketKey()
                    );

            if (!commodities.containsKey(
                    key
            )
                    || data.bucketStart()
                    <= 0L) {
                continue;
            }

            BucketKey bucketKey =
                    new BucketKey(
                            key,
                            data.bucketStart()
                    );
            BucketTotals current =
                    buckets.computeIfAbsent(
                            bucketKey,
                            ignored ->
                                    new BucketTotals()
                    );

            current.unitsSold =
                    Math.max(
                            current.unitsSold,
                            Math.max(
                                    0L,
                                    data.unitsSold()
                            )
                    );
            current.payoutCents =
                    Math.max(
                            current.payoutCents,
                            Math.max(
                                    0L,
                                    data.payoutCents()
                            )
                    );
        }

        rollingDirty = true;
    }



    private synchronized void finishPersistentReset(
            long generation,
            CommodityMarketStorage storage,
            long resetAt,
            CommodityMarketStorage.ResetResult result,
            Exception failure,
            Consumer<ResetCompletion> completion
    ) {
        if (generation != storageGeneration
                || storage != commodityStorage) {
            mergeResetPendingBuckets(
                    false
            );
            pendingResetAt = 0L;
            resetInFlight = false;

            core.getLogger().warning(
                    "Sell market reset result became stale because the "
                            + "market storage generation changed — retry reset"
            );
            completeReset(
                    completion,
                    new ResetCompletion(
                            false,
                            false
                    )
            );
            return;
        }

        if (failure != null
                || result == null) {
            mergeResetPendingBuckets(
                    false
            );
            pendingResetAt = 0L;
            resetInFlight = false;

            core.getLogger().log(
                    Level.SEVERE,
                    "Sell market reset failed before a durable tombstone "
                            + "could be written — existing market data was kept",
                    failure
            );
            completeReset(
                    completion,
                    new ResetCompletion(
                            false,
                            false
                    )
            );
            return;
        }

        marketResetAt =
                resetAt;
        pendingResetAt = 0L;

        states.clear();
        buckets.clear();
        dirtyStates.clear();
        dirtyBuckets.clear();
        rollingCache = Map.of();
        rollingBuiltAt = 0L;
        rollingDirty = true;
        lastYamlSnapshotAt = 0L;
        lastRepriceAt = 0L;
        lastFeaturedRotationAt = 0L;

        ensureCommodityStates();
        mergeResetPendingBuckets(
                true
        );

        /*
         * Reprice only after post-request sales have been replayed so the new
         * baseline cannot pretend those legitimate sales never happened.
         */
        repriceNow(
                Math.max(
                        resetAt + 1L,
                        System.currentTimeMillis()
                ),
                true
        );
        rotateFeaturedNow(
                Math.max(
                        resetAt + 1L,
                        System.currentTimeMillis()
                ),
                true
        );
        markAllStatesDirty();
        markPricesChanged();
        resetInFlight = false;

        if (!result.sqlCleared()) {
            sqlReady = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + SQL_RETRY_MILLIS;

            core.getLogger().log(
                    Level.WARNING,
                    "Sell market reset is durable through the recovery "
                            + "tombstone, but stale SQL rows could not be "
                            + "deleted yet — they will be ignored and SQL "
                            + "will retry",
                    result.sqlFailure()
            );
        }

        flushAsync();

        core.getLogger().info(
                "Sell commodity market reset completed durably"
        );
        completeReset(
                completion,
                new ResetCompletion(
                        true,
                        result.sqlCleared()
                )
        );
    }

    private void completeReset(
            Consumer<ResetCompletion> completion,
            ResetCompletion result
    ) {
        if (completion == null) {
            return;
        }

        try {
            completion.accept(
                    result
            );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Sell market reset completion callback failed",
                    exception
            );
        }
    }

    private void mergeResetPendingBuckets(
            boolean intoFreshMarket
    ) {
        if (resetPendingBuckets.isEmpty()) {
            return;
        }

        for (Map.Entry<BucketKey, BucketTotals> entry
                : resetPendingBuckets.entrySet()) {
            BucketTotals target =
                    buckets.computeIfAbsent(
                            entry.getKey(),
                            ignored ->
                                    new BucketTotals()
                    );
            BucketTotals pending =
                    entry.getValue();

            target.unitsSold =
                    safeAdd(
                            target.unitsSold,
                            pending.unitsSold
                    );
            target.payoutCents =
                    safeAdd(
                            target.payoutCents,
                            pending.payoutCents
                    );
            dirtyBuckets.add(
                    entry.getKey()
            );
        }

        resetPendingBuckets.clear();
        rollingDirty = true;

        if (!intoFreshMarket) {
            markPricesChanged();
        }
    }

    private void flushAsync() {
        if (flushInFlight
                || resetInFlight
                || persistenceExecutor.isShutdown()) {
            return;
        }

        long now =
                System.currentTimeMillis();
        boolean snapshotDue =
                now - lastYamlSnapshotAt
                        >= yamlSnapshotMillis;
        CommodityMarketStorage.SaveBatch batch =
                snapshotDue
                        ? createFullCommodityBatch()
                        : createCommodityBatch();

        if (batch.empty()) {
            lastFlushAt = now;
            playerHistoryStore.flushIfDirty();
            return;
        }

        flushInFlight = true;
        long generation =
                storageGeneration;
        CommodityMarketStorage storage =
                commodityStorage;
        boolean attemptSql =
                sqlReady
                        && storage.sqlConfigured();

        try {
            persistenceExecutor.execute(
                    () -> {
                        PersistenceResult result =
                                persistBatch(
                                        storage,
                                        batch,
                                        attemptSql,
                                        snapshotDue
                                );

                        dispatchMain(
                                () -> finishFlush(
                                        generation,
                                        storage,
                                        batch,
                                        result.sqlSaved(),
                                        result.yamlSaved(),
                                        snapshotDue,
                                        result.sqlFailure(),
                                        result.yamlFailure()
                                )
                        );
                    }
            );
        } catch (RejectedExecutionException exception) {
            flushInFlight = false;
        }

        playerHistoryStore.flushIfDirty();
    }

    private PersistenceResult persistBatch(
            CommodityMarketStorage storage,
            CommodityMarketStorage.SaveBatch batch,
            boolean attemptSql,
            boolean snapshotDue
    ) {
        Exception sqlFailure = null;
        Exception yamlFailure = null;
        boolean sqlSaved = false;
        boolean yamlSaved = false;

        if (attemptSql) {
            try {
                storage.saveSql(batch);
                sqlSaved = true;
            } catch (Exception exception) {
                sqlFailure = exception;
            }
        }

        if (!sqlSaved
                || snapshotDue) {
            try {
                storage.saveYaml(batch);
                yamlSaved = true;
            } catch (Exception exception) {
                yamlFailure = exception;
            }
        }

        return new PersistenceResult(
                sqlSaved,
                yamlSaved,
                sqlFailure,
                yamlFailure
        );
    }

    private synchronized void finishFlush(
            long generation,
            CommodityMarketStorage storage,
            CommodityMarketStorage.SaveBatch batch,
            boolean sqlSaved,
            boolean yamlSaved,
            boolean snapshotDue,
            Exception sqlFailure,
            Exception yamlFailure
    ) {
        flushInFlight = false;
        lastFlushAt =
                System.currentTimeMillis();

        if (generation != storageGeneration
                || storage != commodityStorage) {
            return;
        }

        if (sqlSaved || yamlSaved) {
            clearCommodityDirty(batch);
        }

        if (yamlSaved && snapshotDue) {
            lastYamlSnapshotAt =
                    System.currentTimeMillis();
        }

        if (yamlFailure != null) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not save Sell commodity recovery snapshot",
                    yamlFailure
            );
        }

        if (sqlFailure != null) {
            sqlReady = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + SQL_RETRY_MILLIS;

            core.getLogger().log(
                    Level.WARNING,
                    "Could not save Sell commodity market database — "
                            + "recovery snapshot was attempted",
                    sqlFailure
            );
        }
    }

    private void persistFinalBatch(
            CommodityMarketStorage storage,
            CommodityMarketStorage.SaveBatch batch,
            boolean attemptSql
    ) {
        boolean persisted = false;

        if (attemptSql) {
            try {
                storage.saveSql(batch);
                persisted = true;
            } catch (Exception exception) {
                core.getLogger().log(
                        Level.WARNING,
                        "Could not save Sell commodity market database during shutdown",
                        exception
                );
            }
        }

        try {
            storage.saveYaml(batch);
            persisted = true;
        } catch (Exception exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save Sell commodity recovery snapshot during shutdown",
                    exception
            );
        }

        if (!persisted) {
            core.getLogger().severe(
                    "Sell market final snapshot was not persisted"
            );
        }
    }

    private void dispatchMain(
            Runnable task
    ) {
        if (task == null
                || !core.isEnabled()) {
            return;
        }

        try {
            core.getServer()
                    .getScheduler()
                    .runTask(
                            core,
                            task
                    );
        } catch (RuntimeException ignored) {
            /* Plugin shutdown owns the final persistence boundary directly. */
        }
    }

    private CommodityMarketStorage.SaveBatch
    createResetPendingBatch() {
        List<CommodityMarketStorage.BucketData> bucketData =
                new ArrayList<>(
                        resetPendingBuckets.size()
                );

        for (Map.Entry<BucketKey, BucketTotals> entry
                : resetPendingBuckets.entrySet()) {
            BucketKey key =
                    entry.getKey();
            BucketTotals bucket =
                    entry.getValue();

            bucketData.add(
                    new CommodityMarketStorage.BucketData(
                            key.marketKey(),
                            key.bucketStart(),
                            bucket.unitsSold,
                            bucket.payoutCents
                    )
            );
        }

        return new CommodityMarketStorage.SaveBatch(
                List.of(),
                List.copyOf(bucketData),
                System.currentTimeMillis()
                        - retentionMillis
        );
    }

    private CommodityMarketStorage.SaveBatch
    createCommodityBatch() {
        List<CommodityMarketStorage.MarketStateData>
                stateData =
                new ArrayList<>();
        List<CommodityMarketStorage.BucketData>
                bucketData =
                new ArrayList<>();

        for (String key : dirtyStates) {
            MarketState state =
                    states.get(key);

            if (state == null) {
                continue;
            }

            stateData.add(
                    new CommodityMarketStorage
                            .MarketStateData(
                            key,
                            state.marketMultiplier,
                            state.featuredMultiplier,
                            state.featuredUntil,
                            state.lastRepricedAt,
                            state.targetUnitsPerDay
                    )
            );
        }

        for (BucketKey key
                : dirtyBuckets) {
            BucketTotals bucket =
                    buckets.get(key);

            if (bucket == null) {
                continue;
            }

            bucketData.add(
                    new CommodityMarketStorage
                            .BucketData(
                            key.marketKey(),
                            key.bucketStart(),
                            bucket.unitsSold,
                            bucket.payoutCents
                    )
            );
        }

        return new CommodityMarketStorage
                .SaveBatch(
                List.copyOf(
                        stateData
                ),
                List.copyOf(
                        bucketData
                ),
                System.currentTimeMillis()
                        - retentionMillis
        );
    }



    private CommodityMarketStorage.SaveBatch
    createFullCommodityBatch() {
        List<CommodityMarketStorage.MarketStateData>
                stateData =
                new ArrayList<>();
        List<CommodityMarketStorage.BucketData>
                bucketData =
                new ArrayList<>();

        for (Map.Entry<String, MarketState> entry
                : states.entrySet()) {
            MarketState state =
                    entry.getValue();

            stateData.add(
                    new CommodityMarketStorage
                            .MarketStateData(
                            entry.getKey(),
                            state.marketMultiplier,
                            state.featuredMultiplier,
                            state.featuredUntil,
                            state.lastRepricedAt,
                            state.targetUnitsPerDay
                    )
            );
        }

        for (Map.Entry<BucketKey, BucketTotals> entry
                : buckets.entrySet()) {
            BucketKey key =
                    entry.getKey();
            BucketTotals bucket =
                    entry.getValue();

            bucketData.add(
                    new CommodityMarketStorage
                            .BucketData(
                            key.marketKey(),
                            key.bucketStart(),
                            bucket.unitsSold,
                            bucket.payoutCents
                    )
            );
        }

        return new CommodityMarketStorage
                .SaveBatch(
                List.copyOf(stateData),
                List.copyOf(bucketData),
                System.currentTimeMillis()
                        - retentionMillis
        );
    }

    private void clearCommodityDirty(
            CommodityMarketStorage.SaveBatch batch
    ) {
        for (CommodityMarketStorage.MarketStateData saved
                : batch.states()) {
            MarketState current =
                    states.get(
                            saved.marketKey()
                    );

            if (current == null) {
                continue;
            }

            if (current.marketMultiplier
                    == saved.marketMultiplier()
                    && current.featuredMultiplier
                    == saved.featuredMultiplier()
                    && current.featuredUntil
                    == saved.featuredUntil()
                    && current.lastRepricedAt
                    == saved.lastRepricedAt()
                    && current.targetUnitsPerDay
                    == saved.targetUnitsPerDay()) {
                dirtyStates.remove(
                        saved.marketKey()
                );
            }
        }

        for (CommodityMarketStorage.BucketData saved
                : batch.buckets()) {
            BucketKey key =
                    new BucketKey(
                            saved.marketKey(),
                            saved.bucketStart()
                    );
            BucketTotals current =
                    buckets.get(key);

            if (current != null
                    && current.unitsSold
                    == saved.unitsSold()
                    && current.payoutCents
                    == saved.payoutCents()) {
                dirtyBuckets.remove(key);
            }
        }
    }



    private void markEverythingDirty() {
        dirtyStates.addAll(
                states.keySet()
        );
        dirtyBuckets.addAll(
                buckets.keySet()
        );
    }

    private void markAllStatesDirty() {
        dirtyStates.addAll(
                states.keySet()
        );
    }

    private void pruneInMemory(
            long before
    ) {
        List<BucketKey> old =
                buckets.keySet()
                        .stream()
                        .filter(
                                key ->
                                        key.bucketStart()
                                                < before
                        )
                        .toList();

        for (BucketKey key : old) {
            buckets.remove(key);
            dirtyBuckets.remove(key);
        }

        if (!old.isEmpty()) {
            rollingDirty = true;
        }
    }

    private long bucketStart(
            long timestamp,
            long resetCutoff
    ) {
        long safe =
                Math.max(
                        0L,
                        timestamp
                );
        long aligned =
                safe
                        - (safe % bucketMillis);

        /*
         * A reset can occur in the middle of an hourly bucket. Post-reset
         * sales receive a one-off bucket beginning just after the tombstone so
         * stale pre-reset data from the same hour can never merge back in.
         */
        if (resetCutoff > 0L
                && safe >= resetCutoff
                && aligned <= resetCutoff) {
            return resetCutoff + 1L;
        }

        return aligned;
    }

    private void markPricesChanged() {
        priceRevision =
                priceRevision == Long.MAX_VALUE
                        ? 1L
                        : priceRevision + 1L;
    }

    private void normalizeWeights() {
        double total =
                weightSixHours
                        + weightTwentyFourHours
                        + weightSevenDays;

        if (total <= 0.0D) {
            weightSixHours = 0.60D;
            weightTwentyFourHours = 0.30D;
            weightSevenDays = 0.10D;
            return;
        }

        weightSixHours /= total;
        weightTwentyFourHours /= total;
        weightSevenDays /= total;
    }

    private double safeMultiplier(
            double value
    ) {
        if (!Double.isFinite(value)
                || value <= 0.0D) {
            return 1.0D;
        }

        return roundMultiplier(value);
    }

    private double roundMultiplier(
            double value
    ) {
        return Math.round(
                value * 10_000.0D
        ) / 10_000.0D;
    }

    private double interpolate(
            double start,
            double end,
            double progress
    ) {
        double safeProgress =
                clamp(
                        progress,
                        0.0D,
                        1.0D
                );

        return start
                + ((end - start)
                * safeProgress);
    }

    private double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)) {
            return minimum;
        }

        return Math.clamp(
                value,
                minimum,
                maximum
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

    private long safeMultiply(
            long first,
            long second
    ) {
        try {
            return Math.multiplyExact(
                    first,
                    second
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean isFarmMetaCategory(
            String category
    ) {
        if (category == null) {
            return false;
        }

        return switch (category.toLowerCase(Locale.ROOT)) {
            case "farming",
                 "mob_drops",
                 "wood" -> true;
            default -> false;
        };
    }

    private String normalizeKey(
            String raw
    ) {
        if (raw == null
                || raw.isBlank()) {
            return "";
        }

        return raw.trim()
                .toUpperCase(
                        Locale.ROOT
                );
    }

    private int deterministicOrder(
            String key,
            long now
    ) {
        long rotation =
                now / Math.max(
                        1L,
                        featuredRotationMillis
                );

        return (key
                + ":"
                + rotation)
                .hashCode();
    }

    private static final class MarketState {
        private double marketMultiplier =
                1.0D;
        private double featuredMultiplier =
                1.0D;
        private long featuredUntil;
        private long lastRepricedAt;
        private long targetUnitsPerDay =
                1L;
    }

    private static final class BucketTotals {
        private long unitsSold;
        private long payoutCents;
    }

    private static final class MutableRollingSupply {
        private long sixHoursUnits;
        private long sixHoursPayoutCents;
        private long dayUnits;
        private long dayPayoutCents;
        private long weekUnits;
        private long weekPayoutCents;

        private RollingSupply snapshot() {
            return new RollingSupply(
                    sixHoursUnits,
                    sixHoursPayoutCents,
                    dayUnits,
                    dayPayoutCents,
                    weekUnits,
                    weekPayoutCents
            );
        }
    }

    private static final class CommodityAccumulator {
        private final String key;
        private boolean enabled;
        private boolean farmMeta;
        private long targetUnitsPerDay;
        private double minimumMultiplier =
                0.01D;
        private double maximumMultiplier =
                Double.MAX_VALUE;

        private CommodityAccumulator(
                String key
        ) {
            this.key = key;
        }

        private void add(
                MarketDefinition definition,
                long units
        ) {
            enabled |=
                    definition.marketEnabled();
            farmMeta |=
                    isFarmMetaCategory(
                            definition.category()
                    );

            targetUnitsPerDay =
                    Math.max(
                            targetUnitsPerDay,
                            safeMultiplyStatic(
                                    Math.max(
                                            1L,
                                            definition
                                            .targetUnitsPerDay()
                                    ),
                                    Math.max(
                                            1L,
                                            units
                                    )
                            )
                    );

            minimumMultiplier =
                    Math.max(
                            minimumMultiplier,
                            definition
                                    .minimumMultiplier()
                    );
            maximumMultiplier =
                    Math.min(
                            maximumMultiplier,
                            definition
                                    .maximumMultiplier()
                    );
        }

        private CommodityDefinition build() {
            double maximum =
                    maximumMultiplier
                    == Double.MAX_VALUE
                            ? Math.max(
                            1.0D,
                            minimumMultiplier
                    )
                            : Math.max(
                            minimumMultiplier,
                            maximumMultiplier
                    );

            return new CommodityDefinition(
                    key,
                    enabled,
                    farmMeta,
                    Math.max(
                            1L,
                            targetUnitsPerDay
                    ),
                    minimumMultiplier,
                    maximum
            );
        }

        private static long safeMultiplyStatic(
                long first,
                long second
        ) {
            try {
                return Math.multiplyExact(
                        first,
                        second
                );
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        }
    }

    private record CommodityDefinition(
            String key,
            boolean enabled,
            boolean farmMeta,
            long targetUnitsPerDay,
            double minimumMultiplier,
            double maximumMultiplier
    ) {
    }

    private record RollingSupply(
            long sixHoursUnits,
            long sixHoursPayoutCents,
            long dayUnits,
            long dayPayoutCents,
            long weekUnits,
            long weekPayoutCents
    ) {
        private static final RollingSupply ZERO =
                new RollingSupply(
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L
                );
    }

    private record PersistenceResult(
            boolean sqlSaved,
            boolean yamlSaved,
            Exception sqlFailure,
            Exception yamlFailure
    ) {
    }

    private record BucketKey(
            String marketKey,
            long bucketStart
    ) {
    }


}
