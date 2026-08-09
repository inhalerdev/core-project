package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import net.mineacle.core.sell.model.MarketDefinition;
import net.mineacle.core.sell.model.SellHistoryEntry;
import net.mineacle.core.sell.storage.SellMarketRepository;
import net.mineacle.core.sell.storage.SqlSellMarketRepository;
import net.mineacle.core.sell.storage.YamlSellMarketRepository;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class MarketPricingService {

    private static final long SQL_RETRY_MILLIS =
            5L * 60L * 1000L;

    private final Core core;
    private final YamlSellMarketRepository fallbackRepository;
    private final long runtimeStartedAt = System.currentTimeMillis();

    private final Map<Material, MarketState> states =
            new EnumMap<>(Material.class);
    private final Map<BucketKey, BucketTotals> buckets =
            new HashMap<>();
    private final Map<UUID, Map<Material, SellHistoryEntry>> history =
            new HashMap<>();

    private final Set<Material> dirtyStates =
            new HashSet<>();
    private final Set<BucketKey> dirtyBuckets =
            new HashSet<>();
    private final Set<HistoryKey> dirtyHistory =
            new HashSet<>();

    private Map<Material, MarketDefinition> definitions = Map.of();
    private Set<Material> featuredExcluded = Set.of();
    private Set<Material> featuredPool = Set.of();

    private SqlSellMarketRepository sqlRepository;
    private boolean sqlConfigured;
    private boolean sqlReady;
    private boolean sqlConnecting;
    private boolean flushInFlight;
    private boolean started;
    private boolean enabled;
    private boolean pruneAllBuckets;
    private boolean sqlPruneRequired;
    private boolean pricesChanged;

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
    private long sqlGeneration;

    private double weightSixHours;
    private double weightTwentyFourHours;
    private double weightSevenDays;
    private double maximumChangeFraction;
    private int featuredItemCount;
    private double featuredMinimumBoost;
    private double featuredMaximumBoost;

    public MarketPricingService(
            Core core,
            FileConfiguration sellConfig,
            Map<Material, MarketDefinition> definitions
    ) {
        this.core = core;
        this.fallbackRepository =
                new YamlSellMarketRepository(core);

        reloadSettings(sellConfig, definitions);

        try {
            fallbackRepository.initialize();
            mergeSnapshot(
                    fallbackRepository.load(
                            System.currentTimeMillis()
                                    - retentionMillis
                    )
            );
        } catch (Exception exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not load sell-market.yml",
                    exception
            );
        }

        ensureDefinitionStates();
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        attemptSqlConnection();
    }

    public synchronized void reload(
            FileConfiguration sellConfig,
            Map<Material, MarketDefinition> newDefinitions
    ) {
        flushBlocking();
        reloadSettings(sellConfig, newDefinitions);
        ensureDefinitionStates();
        markAllStatesDirty();

        if (started) {
            attemptSqlConnection();
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();

        synchronized (this) {
            if (!enabled) {
                return;
            }

            expireFeatured(now);

            if (now - lastRepriceAt >= refreshMillis) {
                repriceNow(now, false);
            }

            if (now - lastFeaturedRotationAt
                    >= featuredRotationMillis) {
                rotateFeaturedNow(now, false);
            }

            pruneInMemory(now - retentionMillis);

            if (now - lastFlushAt >= flushMillis) {
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
        MarketState state = state(material);
        return state == null ? 1.0D : state.marketMultiplier;
    }

    public synchronized double featuredMultiplier(
            Material material
    ) {
        MarketState state = state(material);

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
        double combined = marketMultiplier(material)
                * featuredMultiplier(material);
        MarketDefinition definition = definitions.get(material);

        if (definition != null) {
            combined = clamp(
                    combined,
                    definition.minimumMultiplier(),
                    definition.maximumMultiplier()
            );
        }

        return roundMultiplier(combined);
    }

    public synchronized boolean isFeatured(Material material) {
        MarketState state = state(material);

        return state != null
                && state.featuredMultiplier > 1.0001D
                && state.featuredUntil
                > System.currentTimeMillis();
    }

    public synchronized long featuredUntil(Material material) {
        MarketState state = state(material);
        return state == null ? 0L : state.featuredUntil;
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
        long cutoff = System.currentTimeMillis()
                - Math.max(0L, windowMillis);
        long total = 0L;

        for (Map.Entry<BucketKey, BucketTotals> entry
                : buckets.entrySet()) {
            if (entry.getKey().material() != material
                    || entry.getKey().bucketStart() < cutoff) {
                continue;
            }

            total = safeAdd(
                    total,
                    entry.getValue().unitsSold
            );
        }

        return total;
    }

    public synchronized long rollingPayoutCents(
            Material material,
            long windowMillis
    ) {
        long cutoff = System.currentTimeMillis()
                - Math.max(0L, windowMillis);
        long total = 0L;

        for (Map.Entry<BucketKey, BucketTotals> entry
                : buckets.entrySet()) {
            if (entry.getKey().material() != material
                    || entry.getKey().bucketStart() < cutoff) {
                continue;
            }

            total = safeAdd(
                    total,
                    entry.getValue().payoutCents
            );
        }

        return total;
    }

    public synchronized double supplyRatio(Material material) {
        MarketDefinition definition = definitions.get(material);

        if (definition == null
                || definition.targetUnitsPerDay() <= 0L) {
            return 1.0D;
        }

        double weightedDaily = weightedDailySupply(material);

        return Math.max(
                0.0D,
                weightedDaily / definition.targetUnitsPerDay()
        );
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

        long bucketStart = bucketStart(soldAt);
        BucketKey bucketKey = new BucketKey(
                material,
                bucketStart
        );
        BucketTotals bucket = buckets.computeIfAbsent(
                bucketKey,
                ignored -> new BucketTotals()
        );

        bucket.unitsSold = safeAdd(bucket.unitsSold, amount);
        bucket.payoutCents = safeAdd(
                bucket.payoutCents,
                payoutCents
        );
        dirtyBuckets.add(bucketKey);

        Map<Material, SellHistoryEntry> playerHistory =
                history.computeIfAbsent(
                        playerId,
                        ignored -> new EnumMap<>(Material.class)
                );
        SellHistoryEntry current =
                playerHistory.get(material);
        long historyAmount = current == null
                ? amount
                : safeAdd(current.amount(), amount);
        long historyCents = current == null
                ? payoutCents
                : safeAdd(
                        current.totalCents(),
                        payoutCents
                );

        playerHistory.put(
                material,
                new SellHistoryEntry(
                        material,
                        historyAmount,
                        historyCents,
                        Math.max(
                                soldAt,
                                current == null
                                        ? 0L
                                        : current.lastSoldMillis()
                        )
                )
        );
        dirtyHistory.add(new HistoryKey(playerId, material));
    }

    public synchronized List<SellHistoryEntry> history(
            UUID playerId
    ) {
        Map<Material, SellHistoryEntry> entries =
                history.get(playerId);

        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        return List.copyOf(entries.values());
    }

    public synchronized void forceReprice() {
        long now = System.currentTimeMillis();
        repriceNow(now, true);
        flushAsync();
    }

    public synchronized void forceFeaturedRotation() {
        long now = System.currentTimeMillis();
        rotateFeaturedNow(now, true);
        flushAsync();
    }

    public synchronized void reset() {
        states.clear();
        buckets.clear();
        dirtyBuckets.clear();
        pruneAllBuckets = true;
        sqlPruneRequired = true;

        ensureDefinitionStates();
        markAllStatesDirty();
        lastRepriceAt = 0L;
        lastFeaturedRotationAt = 0L;
        forceReprice();
        forceFeaturedRotation();
    }

    public synchronized void importHistory(
            UUID playerId,
            Material material,
            long amount,
            long totalCents,
            long lastSoldAt
    ) {
        if (playerId == null
                || material == null
                || amount <= 0L) {
            return;
        }

        Map<Material, SellHistoryEntry> playerHistory =
                history.computeIfAbsent(
                        playerId,
                        ignored -> new EnumMap<>(Material.class)
                );
        SellHistoryEntry current = playerHistory.get(material);

        if (current == null) {
            playerHistory.put(
                    material,
                    new SellHistoryEntry(
                            material,
                            amount,
                            Math.max(0L, totalCents),
                            Math.max(0L, lastSoldAt)
                    )
            );
        } else {
            playerHistory.put(
                    material,
                    new SellHistoryEntry(
                            material,
                            Math.max(current.amount(), amount),
                            Math.max(
                                    current.totalCents(),
                                    totalCents
                            ),
                            Math.max(
                                    current.lastSoldMillis(),
                                    lastSoldAt
                            )
                    )
            );
        }

        dirtyHistory.add(new HistoryKey(playerId, material));
    }

    public synchronized String tier(Material material) {
        double multiplier = combinedMultiplier(material);

        if (isFeatured(material) && multiplier >= 1.20D) {
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

    public synchronized boolean consumePriceChange() {
        boolean changed = pricesChanged;
        pricesChanged = false;
        return changed;
    }

    public synchronized void flushIfDirty() {
        flushAsync();
    }

    public synchronized void shutdown() {
        started = false;
        flushBlocking();

        try {
            fallbackRepository.close();
        } catch (Exception ignored) {
        }

        if (sqlRepository != null) {
            try {
                sqlRepository.close();
            } catch (Exception ignored) {
            }
        }

        sqlReady = false;
        sqlConnecting = false;
    }

    private void reloadSettings(
            FileConfiguration config,
            Map<Material, MarketDefinition> newDefinitions
    ) {
        definitions = Map.copyOf(newDefinitions);
        enabled = config.getBoolean("market.enabled", true);
        bucketMillis = Math.max(
                5L,
                config.getLong(
                        "market.bucket-minutes",
                        60L
                )
        ) * 60L * 1000L;
        retentionMillis = retentionMillis(config);
        refreshMillis = Math.max(
                1L,
                config.getLong(
                        "market.reprice-interval-minutes",
                        15L
                )
        ) * 60L * 1000L;
        featuredRotationMillis = Math.max(
                1L,
                config.getLong(
                        "market.featured.rotation-hours",
                        12L
                )
        ) * 60L * 60L * 1000L;
        flushMillis = Math.max(
                5L,
                config.getLong(
                        "market.flush-seconds",
                        30L
                )
        ) * 1000L;
        maximumChangeFraction = clamp(
                config.getDouble(
                        "market.maximum-change-per-refresh-percent",
                        8.0D
                ) / 100.0D,
                0.001D,
                1.0D
        );
        minimumObservationMillis = Math.max(
                0L,
                config.getLong(
                        "market.minimum-observation-hours",
                        6L
                )
        ) * 60L * 60L * 1000L;
        featuredItemCount = Math.max(
                0,
                config.getInt(
                        "market.featured.active-items",
                        8
                )
        );
        featuredMinimumBoost = clamp(
                config.getDouble(
                        "market.featured.minimum-multiplier",
                        1.10D
                ),
                1.0D,
                10.0D
        );
        featuredMaximumBoost = clamp(
                config.getDouble(
                        "market.featured.maximum-multiplier",
                        1.35D
                ),
                featuredMinimumBoost,
                10.0D
        );

        weightSixHours = Math.max(
                0.0D,
                config.getDouble(
                        "market.weights.last-6-hours",
                        0.60D
                )
        );
        weightTwentyFourHours = Math.max(
                0.0D,
                config.getDouble(
                        "market.weights.last-24-hours",
                        0.30D
                )
        );
        weightSevenDays = Math.max(
                0.0D,
                config.getDouble(
                        "market.weights.last-7-days",
                        0.10D
                )
        );
        normalizeWeights();

        Set<Material> excluded = new HashSet<>();

        for (String raw : config.getStringList(
                "market.featured.excluded-items"
        )) {
            Material material = Material.matchMaterial(raw);

            if (material != null) {
                excluded.add(material);
            }
        }

        featuredExcluded = Set.copyOf(excluded);

        Set<Material> pool = new HashSet<>();

        for (String raw : config.getStringList(
                "market.featured.pool"
        )) {
            Material material = Material.matchMaterial(raw);

            if (material != null) {
                pool.add(material);
            }
        }

        featuredPool = Set.copyOf(pool);
        rebuildSqlRepository(config);
        ensureDefinitionStates();
    }

    private void rebuildSqlRepository(
            FileConfiguration sellConfig
    ) {
        sqlGeneration++;
        SqlSellMarketRepository previous = sqlRepository;

        if (previous != null) {
            try {
                previous.close();
            } catch (Exception ignored) {
            }
        }

        String storage = sellConfig.getString(
                "market.storage",
                "mysql"
        );

        sqlConfigured = storage != null
                && (storage.equalsIgnoreCase("mysql")
                || storage.equalsIgnoreCase("mariadb"));

        if (!sqlConfigured) {
            sqlReady = false;
            sqlConnecting = false;
            sqlRepository = null;
            return;
        }

        String configFile = sellConfig.getString(
                "market.database-config-file",
                "webprofiles.yml"
        );
        File databaseFile = new File(
                core.getDataFolder(),
                configFile == null || configFile.isBlank()
                        ? "webprofiles.yml"
                        : configFile
        );
        FileConfiguration databaseConfiguration =
                YamlConfiguration.loadConfiguration(databaseFile);
        String prefix = sellConfig.getString(
                "market.table-prefix",
                "mineacle_sell"
        );

        sqlRepository = new SqlSellMarketRepository(
                core,
                databaseConfiguration,
                prefix
        );
        sqlReady = false;
        sqlConnecting = false;
        nextSqlRetryAt = 0L;
    }

    private void attemptSqlConnection() {
        if (!started
                || !sqlConfigured
                || sqlRepository == null
                || sqlReady
                || sqlConnecting) {
            return;
        }

        sqlConnecting = true;
        long bucketsSince = System.currentTimeMillis()
                - retentionMillis;
        SqlSellMarketRepository repository = sqlRepository;
        long generation = sqlGeneration;

        core.getServer().getScheduler().runTaskAsynchronously(
                core,
                () -> {
                    SellMarketRepository.Snapshot snapshot = null;
                    Exception failure = null;

                    try {
                        repository.initialize();
                        snapshot = repository.load(bucketsSince);
                    } catch (Exception exception) {
                        failure = exception;
                    }

                    SellMarketRepository.Snapshot loaded = snapshot;
                    Exception error = failure;

                    core.getServer().getScheduler().runTask(
                            core,
                            () -> finishSqlConnection(
                                    repository,
                                    generation,
                                    loaded,
                                    error
                            )
                    );
                }
        );
    }

    private synchronized void finishSqlConnection(
            SqlSellMarketRepository repository,
            long generation,
            SellMarketRepository.Snapshot snapshot,
            Exception failure
    ) {
        if (repository != sqlRepository
                || generation != sqlGeneration) {
            return;
        }

        sqlConnecting = false;

        if (!started) {
            return;
        }

        if (failure != null || snapshot == null) {
            sqlReady = false;
            nextSqlRetryAt = System.currentTimeMillis()
                    + SQL_RETRY_MILLIS;

            core.getLogger().log(
                    Level.WARNING,
                    "Sell market database unavailable — using "
                            + "sell-market.yml and retrying later",
                    failure
            );
            return;
        }

        if (sqlPruneRequired) {
            mergeSnapshot(new SellMarketRepository.Snapshot(
                    List.of(),
                    List.of(),
                    snapshot.history()
            ));
        } else {
            mergeSnapshot(snapshot);
        }

        sqlReady = true;
        nextSqlRetryAt = 0L;
        markEverythingDirty();

        core.getLogger().info(
                "Sell market database connected using "
                        + sqlRepository.name()
        );
        flushAsync();
    }

    private void repriceNow(long now, boolean immediate) {
        if (!enabled) {
            return;
        }

        ensureDefinitionStates();

        for (MarketDefinition definition
                : definitions.values()) {
            if (!definition.marketEnabled()
                    || definition.baseCents() <= 0L) {
                continue;
            }

            MarketState state = states.get(
                    definition.material()
            );
            double ratio = supplyRatio(
                    definition.material()
            );
            double desired = desiredMultiplier(
                    ratio,
                    definition.minimumMultiplier(),
                    definition.maximumMultiplier()
            );
            double confidence = observationConfidence(
                    definition.material(),
                    now
            );
            desired = interpolate(
                    1.0D,
                    desired,
                    confidence
            );
            double next = immediate
                    ? desired
                    : smooth(
                            state.marketMultiplier,
                            desired
                    );

            next = clamp(
                    next,
                    definition.minimumMultiplier(),
                    definition.maximumMultiplier()
            );

            double rounded = roundMultiplier(next);

            if (Math.abs(state.marketMultiplier - rounded)
                    >= 0.0001D) {
                pricesChanged = true;
            }

            state.marketMultiplier = rounded;
            state.targetUnitsPerDay =
                    definition.targetUnitsPerDay();
            state.lastRepricedAt = now;
            dirtyStates.add(definition.material());
        }

        lastRepriceAt = now;
    }

    private void rotateFeaturedNow(
            long now,
            boolean immediate
    ) {
        if (!enabled || featuredItemCount <= 0) {
            clearFeatured(now);
            lastFeaturedRotationAt = now;
            return;
        }

        clearFeatured(now);

        List<Material> candidates = definitions.values()
                .stream()
                .filter(MarketDefinition::marketEnabled)
                .filter(definition ->
                        definition.baseCents() > 0L
                )
                .map(MarketDefinition::material)
                .filter(material ->
                        !featuredExcluded.contains(material)
                )
                .filter(material ->
                        featuredPool.isEmpty()
                                || featuredPool.contains(material)
                )
                .sorted(
                        Comparator
                                .comparingDouble(this::supplyRatio)
                                .thenComparingInt(material ->
                                        deterministicOrder(
                                                material,
                                                now
                                        )
                                )
                )
                .toList();

        int poolLimit = Math.min(
                candidates.size(),
                Math.max(
                        featuredItemCount,
                        featuredItemCount * 3
                )
        );
        List<Material> shortagePool =
                new ArrayList<>(
                        candidates.subList(0, poolLimit)
                );

        shortagePool.sort(
                Comparator.comparingInt(material ->
                        deterministicOrder(material, now)
                )
        );

        long until = now + featuredRotationMillis;
        int applied = 0;

        for (Material material : shortagePool) {
            if (applied >= featuredItemCount) {
                break;
            }

            double ratio = Math.min(1.0D, supplyRatio(material));
            double shortage = 1.0D - ratio;
            double multiplier = featuredMinimumBoost
                    + ((featuredMaximumBoost
                    - featuredMinimumBoost) * shortage);

            MarketState state = states.get(material);
            double rounded = roundMultiplier(multiplier);

            if (Math.abs(state.featuredMultiplier - rounded)
                    >= 0.0001D
                    || state.featuredUntil != until) {
                pricesChanged = true;
            }

            state.featuredMultiplier = rounded;
            state.featuredUntil = until;
            dirtyStates.add(material);
            applied++;
        }

        lastFeaturedRotationAt = now;

        if (immediate) {
            repriceNow(now, true);
        }
    }

    private void clearFeatured(long now) {
        for (Map.Entry<Material, MarketState> entry
                : states.entrySet()) {
            MarketState state = entry.getValue();

            if (state.featuredMultiplier != 1.0D
                    || state.featuredUntil != 0L) {
                state.featuredMultiplier = 1.0D;
                state.featuredUntil = 0L;
                pricesChanged = true;
                dirtyStates.add(entry.getKey());
            }
        }
    }

    private void expireFeatured(long now) {
        for (Map.Entry<Material, MarketState> entry
                : states.entrySet()) {
            MarketState state = entry.getValue();

            if (state.featuredUntil > 0L
                    && state.featuredUntil <= now) {
                state.featuredUntil = 0L;
                state.featuredMultiplier = 1.0D;
                pricesChanged = true;
                dirtyStates.add(entry.getKey());
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
                    Math.min(maximum, 1.15D),
                    (ratio - 0.25D) / 0.50D
            );
        }

        if (ratio <= 1.25D) {
            return interpolate(
                    Math.min(maximum, 1.15D),
                    Math.max(minimum, 0.90D),
                    (ratio - 0.75D) / 0.50D
            );
        }

        if (ratio <= 2.0D) {
            return interpolate(
                    Math.max(minimum, 0.90D),
                    minimum,
                    (ratio - 1.25D) / 0.75D
            );
        }

        return minimum;
    }

    private double smooth(double current, double desired) {
        if (current <= 0.0D) {
            return desired;
        }

        double maximumMove = Math.max(
                0.01D,
                current * maximumChangeFraction
        );

        if (desired > current) {
            return Math.min(desired, current + maximumMove);
        }

        return Math.max(desired, current - maximumMove);
    }

    private double observationConfidence(
            Material material,
            long now
    ) {
        if (minimumObservationMillis <= 0L) {
            return 1.0D;
        }

        long oldest = Long.MAX_VALUE;

        for (BucketKey key : buckets.keySet()) {
            if (key.material() == material) {
                oldest = Math.min(oldest, key.bucketStart());
            }
        }

        long observed = oldest == Long.MAX_VALUE
                ? Math.max(0L, now - runtimeStartedAt)
                : Math.max(
                        0L,
                        now - oldest + bucketMillis
                );

        return clamp(
                observed / (double) minimumObservationMillis,
                0.0D,
                1.0D
        );
    }

    private double weightedDailySupply(Material material) {
        double sixHours = rollingUnits(
                material,
                6L * 60L * 60L * 1000L
        ) * 4.0D;
        double twentyFourHours = rollingUnits(
                material,
                24L * 60L * 60L * 1000L
        );
        double sevenDays = rollingUnits(
                material,
                7L * 24L * 60L * 60L * 1000L
        ) / 7.0D;

        return (sixHours * weightSixHours)
                + (twentyFourHours * weightTwentyFourHours)
                + (sevenDays * weightSevenDays);
    }

    private void normalizeWeights() {
        double total = weightSixHours
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

    private void ensureDefinitionStates() {
        for (MarketDefinition definition
                : definitions.values()) {
            MarketState state = states.computeIfAbsent(
                    definition.material(),
                    ignored -> new MarketState()
            );

            if (!definition.marketEnabled()
                    && state.marketMultiplier != 1.0D) {
                state.marketMultiplier = 1.0D;
                pricesChanged = true;
                dirtyStates.add(definition.material());
            }

            state.targetUnitsPerDay =
                    definition.targetUnitsPerDay();
        }
    }

    private MarketState state(Material material) {
        if (material == null) {
            return null;
        }

        MarketDefinition definition = definitions.get(material);

        if (definition == null) {
            return null;
        }

        MarketState state = states.computeIfAbsent(
                material,
                ignored -> new MarketState()
        );
        state.targetUnitsPerDay =
                definition.targetUnitsPerDay();
        return state;
    }

    private void mergeSnapshot(
            SellMarketRepository.Snapshot snapshot
    ) {
        if (snapshot == null) {
            return;
        }

        for (SellMarketRepository.MarketStateData data
                : snapshot.states()) {
            Material material = material(data.material());

            if (material == null) {
                continue;
            }

            MarketState current = states.get(material);

            if (current == null
                    || data.lastRepricedAt()
                    >= current.lastRepricedAt) {
                MarketState merged = new MarketState();
                merged.marketMultiplier = safeMultiplier(
                        data.marketMultiplier()
                );
                merged.featuredMultiplier = safeMultiplier(
                        data.featuredMultiplier()
                );
                merged.featuredUntil =
                        Math.max(0L, data.featuredUntil());
                merged.lastRepricedAt =
                        Math.max(0L, data.lastRepricedAt());
                merged.targetUnitsPerDay =
                        Math.max(
                                1L,
                                data.targetUnitsPerDay()
                        );
                states.put(material, merged);
                lastRepriceAt = Math.max(
                        lastRepriceAt,
                        merged.lastRepricedAt
                );

                if (merged.featuredUntil > 0L) {
                    lastFeaturedRotationAt = Math.max(
                            lastFeaturedRotationAt,
                            merged.featuredUntil
                                    - featuredRotationMillis
                    );
                }
            }
        }

        for (SellMarketRepository.BucketData data
                : snapshot.buckets()) {
            Material material = material(data.material());

            if (material == null
                    || data.bucketStart() <= 0L) {
                continue;
            }

            BucketKey key = new BucketKey(
                    material,
                    data.bucketStart()
            );
            BucketTotals current = buckets.computeIfAbsent(
                    key,
                    ignored -> new BucketTotals()
            );

            current.unitsSold = Math.max(
                    current.unitsSold,
                    Math.max(0L, data.unitsSold())
            );
            current.payoutCents = Math.max(
                    current.payoutCents,
                    Math.max(0L, data.payoutCents())
            );
        }

        for (SellMarketRepository.HistoryData data
                : snapshot.history()) {
            Material material = material(data.material());

            if (material == null || data.playerId() == null) {
                continue;
            }

            Map<Material, SellHistoryEntry> playerHistory =
                    history.computeIfAbsent(
                            data.playerId(),
                            ignored ->
                                    new EnumMap<>(Material.class)
                    );
            SellHistoryEntry current =
                    playerHistory.get(material);

            if (current == null) {
                playerHistory.put(
                        material,
                        new SellHistoryEntry(
                                material,
                                Math.max(0L, data.amount()),
                                Math.max(0L, data.totalCents()),
                                Math.max(0L, data.lastSoldAt())
                        )
                );
                continue;
            }

            playerHistory.put(
                    material,
                    new SellHistoryEntry(
                            material,
                            Math.max(
                                    current.amount(),
                                    data.amount()
                            ),
                            Math.max(
                                    current.totalCents(),
                                    data.totalCents()
                            ),
                            Math.max(
                                    current.lastSoldMillis(),
                                    data.lastSoldAt()
                            )
                    )
            );
        }
    }

    private void flushAsync() {
        if (flushInFlight) {
            return;
        }

        SellMarketRepository.SaveBatch batch =
                createSaveBatch();

        if (batch.empty()) {
            lastFlushAt = System.currentTimeMillis();
            return;
        }

        flushInFlight = true;
        SqlSellMarketRepository repository =
                sqlReady ? sqlRepository : null;
        long generation = sqlGeneration;

        core.getServer().getScheduler().runTaskAsynchronously(
                core,
                () -> {
                    Exception fallbackFailure = null;
                    Exception sqlFailure = null;

                    try {
                        fallbackRepository.save(batch);
                    } catch (Exception exception) {
                        fallbackFailure = exception;
                    }

                    if (repository != null) {
                        try {
                            repository.save(batch);
                        } catch (Exception exception) {
                            sqlFailure = exception;
                        }
                    }

                    Exception finalFallbackFailure =
                            fallbackFailure;
                    Exception finalSqlFailure = sqlFailure;

                    core.getServer().getScheduler().runTask(
                            core,
                            () -> finishFlush(
                                    batch,
                                    repository,
                                    generation,
                                    finalFallbackFailure,
                                    finalSqlFailure
                            )
                    );
                }
        );
    }

    private synchronized void finishFlush(
            SellMarketRepository.SaveBatch batch,
            SqlSellMarketRepository repository,
            long generation,
            Exception fallbackFailure,
            Exception sqlFailure
    ) {
        flushInFlight = false;
        lastFlushAt = System.currentTimeMillis();
        boolean currentSql = repository != null
                && repository == sqlRepository
                && generation == sqlGeneration;

        if (fallbackFailure == null) {
            clearSavedDirtyEntries(batch);
        }

        if (currentSql
                && sqlFailure == null
                && batch.pruneBucketsBefore()
                == Long.MAX_VALUE) {
            sqlPruneRequired = false;
        }

        if (fallbackFailure != null) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save sell-market.yml",
                    fallbackFailure
            );
        }

        if (sqlFailure != null && currentSql) {
            sqlReady = false;
            nextSqlRetryAt = System.currentTimeMillis()
                    + SQL_RETRY_MILLIS;

            core.getLogger().log(
                    Level.WARNING,
                    "Could not save Sell market database — "
                            + "YAML fallback remains active",
                    sqlFailure
            );

            if (fallbackFailure == null) {
                clearSavedDirtyEntries(batch);
            }
        }
    }

    private void flushBlocking() {
        SellMarketRepository.SaveBatch batch =
                createSaveBatch();

        if (batch.empty()) {
            return;
        }

        boolean fallbackSaved = false;

        try {
            fallbackRepository.save(batch);
            fallbackSaved = true;
        } catch (Exception exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save sell-market.yml during shutdown",
                    exception
            );
        }

        if (sqlReady && sqlRepository != null) {
            try {
                sqlRepository.save(batch);

                if (batch.pruneBucketsBefore()
                        == Long.MAX_VALUE) {
                    sqlPruneRequired = false;
                }
            } catch (Exception exception) {
                core.getLogger().log(
                        Level.WARNING,
                        "Could not save Sell market database "
                                + "during shutdown",
                        exception
                );
            }
        }

        if (fallbackSaved) {
            clearSavedDirtyEntries(batch);
        }
    }

    private SellMarketRepository.SaveBatch createSaveBatch() {
        List<SellMarketRepository.MarketStateData> stateData =
                new ArrayList<>();
        List<SellMarketRepository.BucketData> bucketData =
                new ArrayList<>();
        List<SellMarketRepository.HistoryData> historyData =
                new ArrayList<>();

        for (Material material : dirtyStates) {
            MarketState state = states.get(material);

            if (state != null) {
                stateData.add(stateData(material, state));
            }
        }

        for (BucketKey key : dirtyBuckets) {
            BucketTotals bucket = buckets.get(key);

            if (bucket != null) {
                bucketData.add(new SellMarketRepository.BucketData(
                        key.material().name(),
                        key.bucketStart(),
                        bucket.unitsSold,
                        bucket.payoutCents
                ));
            }
        }

        for (HistoryKey key : dirtyHistory) {
            Map<Material, SellHistoryEntry> playerHistory =
                    history.get(key.playerId());
            SellHistoryEntry entry = playerHistory == null
                    ? null
                    : playerHistory.get(key.material());

            if (entry != null) {
                historyData.add(
                        new SellMarketRepository.HistoryData(
                                key.playerId(),
                                key.material().name(),
                                entry.amount(),
                                entry.totalCents(),
                                entry.lastSoldMillis()
                        )
                );
            }
        }

        long pruneBefore = pruneAllBuckets
                || (sqlReady && sqlPruneRequired)
                ? Long.MAX_VALUE
                : System.currentTimeMillis() - retentionMillis;

        return new SellMarketRepository.SaveBatch(
                List.copyOf(stateData),
                List.copyOf(bucketData),
                List.copyOf(historyData),
                pruneBefore
        );
    }

    private void clearSavedDirtyEntries(
            SellMarketRepository.SaveBatch batch
    ) {
        if (batch.pruneBucketsBefore() == Long.MAX_VALUE) {
            pruneAllBuckets = false;
        }
        for (SellMarketRepository.MarketStateData saved
                : batch.states()) {
            Material material = material(saved.material());
            MarketState current = states.get(material);

            if (material != null
                    && current != null
                    && stateData(material, current).equals(saved)) {
                dirtyStates.remove(material);
            }
        }

        for (SellMarketRepository.BucketData saved
                : batch.buckets()) {
            Material material = material(saved.material());

            if (material == null) {
                continue;
            }

            BucketKey key = new BucketKey(
                    material,
                    saved.bucketStart()
            );
            BucketTotals current = buckets.get(key);

            if (current != null
                    && current.unitsSold == saved.unitsSold()
                    && current.payoutCents
                    == saved.payoutCents()) {
                dirtyBuckets.remove(key);
            }
        }

        for (SellMarketRepository.HistoryData saved
                : batch.history()) {
            Material material = material(saved.material());

            if (material == null) {
                continue;
            }

            HistoryKey key = new HistoryKey(
                    saved.playerId(),
                    material
            );
            Map<Material, SellHistoryEntry> playerHistory =
                    history.get(saved.playerId());
            SellHistoryEntry current = playerHistory == null
                    ? null
                    : playerHistory.get(material);

            if (current != null
                    && current.amount() == saved.amount()
                    && current.totalCents()
                    == saved.totalCents()
                    && current.lastSoldMillis()
                    == saved.lastSoldAt()) {
                dirtyHistory.remove(key);
            }
        }
    }

    private SellMarketRepository.MarketStateData stateData(
            Material material,
            MarketState state
    ) {
        return new SellMarketRepository.MarketStateData(
                material.name(),
                state.marketMultiplier,
                state.featuredMultiplier,
                state.featuredUntil,
                state.lastRepricedAt,
                state.targetUnitsPerDay
        );
    }

    private void markEverythingDirty() {
        dirtyStates.addAll(states.keySet());
        dirtyBuckets.addAll(buckets.keySet());

        for (Map.Entry<UUID, Map<Material, SellHistoryEntry>>
                player : history.entrySet()) {
            for (Material material : player.getValue().keySet()) {
                dirtyHistory.add(
                        new HistoryKey(
                                player.getKey(),
                                material
                        )
                );
            }
        }
    }

    private void markAllStatesDirty() {
        dirtyStates.addAll(states.keySet());
    }

    private void pruneInMemory(long before) {
        List<BucketKey> old = buckets.keySet()
                .stream()
                .filter(key -> key.bucketStart() < before)
                .toList();

        for (BucketKey key : old) {
            buckets.remove(key);
            dirtyBuckets.remove(key);
        }
    }

    private long bucketStart(long timestamp) {
        long safe = Math.max(0L, timestamp);
        return safe - (safe % bucketMillis);
    }

    private long retentionMillis(
            FileConfiguration config
    ) {
        return Math.max(
                1L,
                config.getLong(
                        "market.retention-days",
                        7L
                )
        ) * 24L * 60L * 60L * 1000L;
    }

    private Material material(String raw) {
        return raw == null
                ? null
                : Material.matchMaterial(raw);
    }

    private double safeMultiplier(double value) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            return 1.0D;
        }

        return roundMultiplier(value);
    }

    private double roundMultiplier(double value) {
        return Math.round(value * 10_000.0D) / 10_000.0D;
    }

    private double interpolate(
            double start,
            double end,
            double progress
    ) {
        double safeProgress = clamp(progress, 0.0D, 1.0D);
        return start + ((end - start) * safeProgress);
    }

    private double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)) {
            return minimum;
        }

        return Math.max(minimum, Math.min(maximum, value));
    }

    private long safeAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private int deterministicOrder(
            Material material,
            long now
    ) {
        long rotation = now / Math.max(
                1L,
                featuredRotationMillis
        );
        return (material.name() + ":" + rotation).hashCode();
    }

    private static final class MarketState {

        private double marketMultiplier = 1.0D;
        private double featuredMultiplier = 1.0D;
        private long featuredUntil;
        private long lastRepricedAt;
        private long targetUnitsPerDay = 1L;
    }

    private static final class BucketTotals {

        private long unitsSold;
        private long payoutCents;
    }

    private record BucketKey(
            Material material,
            long bucketStart
    ) {
    }

    private record HistoryKey(
            UUID playerId,
            Material material
    ) {
    }
}
