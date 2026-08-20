package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.storage.SellLearningStorage;
import net.mineacle.core.sell.storage.SellLearningStorage.ActivityDelta;
import net.mineacle.core.sell.storage.SellLearningStorage.LearningRow;
import net.mineacle.core.sell.storage.SellLearningStorage.MarketTotals;
import net.mineacle.core.sell.storage.SellLearningStorage.SellerTotals;
import net.mineacle.core.sell.storage.SellLearningStorage.WindowSnapshot;
import net.mineacle.core.sell.storage.SellLearningStorage.WriteBatch;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Sell/Worth v10 economic telemetry and shadow-learning engine.
 *
 * <p>The service never changes a live payout. It records aggregate economic
 * evidence, learns supply rates per active-player-hour, manages shadow meta
 * state behind evidence/completion gates, and emits permission-gated admin
 * notices. Once the learner proves stable, the same persisted state can become
 * the authority for a later v10 pricing snapshot.</p>
 */
public final class SellLearningService {

    private static final String STATE_LEARNING = "LEARNING";
    private static final String STATE_STABLE = "STABLE";
    private static final String STATE_SATURATED = "SATURATED";
    private static final String STATE_META = "META";
    private static final String STATE_META_READY = "META_READY";

    private static final String ALERT_PERMISSION =
            "mineaclesell.admin";

    private static final long MINUTE_MILLIS =
            60_000L;
    private static final long HOUR_MILLIS =
            60L * MINUTE_MILLIS;
    private static final long DAY_MILLIS =
            24L * HOUR_MILLIS;
    private static final long SQL_RETRY_MILLIS =
            5L * MINUTE_MILLIS;
    private static final long SHUTDOWN_BUDGET_MILLIS =
            5_000L;

    private final Core core;
    private final SellService sellService;
    private final ScheduledThreadPoolExecutor executor;

    private final Map<Long, MutableActivity> pendingActivity =
            new HashMap<>();
    private final Map<String, Long> concentrationAlertAt =
            new HashMap<>();

    private volatile Map<String, LearningRow> learningState =
            Map.of();

    private SellLearningStorage storage;
    private LearningConfig config;

    private boolean started;
    private boolean closed;
    private boolean sqlReady;
    private boolean sqlConnecting;
    private boolean evaluationQueued;
    private long nextSqlRetryAt;
    private long nextFlushAt;
    private long nextEvaluationAt;
    private long lastPlayerSampleAt;
    private long lastPruneAt;

    public SellLearningService(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
        this.executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "Mineacle-SellLearning"
                                    );
                            thread.setDaemon(true);
                            return thread;
                        }
                );
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        reloadConfiguration();
    }

    public synchronized void start() {
        if (started
                || closed
                || !config.enabled()) {
            return;
        }

        started = true;
        long now = System.currentTimeMillis();
        lastPlayerSampleAt = now;
        nextFlushAt = now + config.flushMillis();
        nextEvaluationAt = now + config.evaluationMillis();
        initializeSqlAsync();

        core.getLogger().info(
                "Sell v10 shadow learning enabled — live payouts remain v9 authority"
        );
    }

    /**
     * Main-thread heartbeat. Database work is only queued from here; it is
     * never executed on the server thread.
     */
    public synchronized void tick() {
        if (!started
                || closed
                || !config.enabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        samplePlayerTime(now);
        trimPending(now);

        if (!sqlReady) {
            if (!sqlConnecting
                    && now >= nextSqlRetryAt) {
                initializeSqlAsync();
            }
            return;
        }

        if (now >= nextFlushAt) {
            nextFlushAt =
                    now + config.flushMillis();
            flushAsync();
        }

        if (now >= nextEvaluationAt) {
            nextEvaluationAt =
                    now + config.evaluationMillis();
            evaluateAsync(
                    captureReferences(),
                    now
            );
        }
    }

    public synchronized void shutdown() {
        if (closed) {
            return;
        }

        closed = true;
        started = false;
        samplePlayerTime(
                System.currentTimeMillis()
        );

        WriteBatch finalBatch =
                takePendingBatch();
        SellLearningStorage currentStorage =
                storage;
        boolean canPersist =
                sqlReady
                        && currentStorage != null
                        && !finalBatch.emptyBatch();

        if (canPersist) {
            try {
                executor.execute(
                        () -> {
                            try {
                                currentStorage.saveBatch(
                                        finalBatch
                                );
                            } catch (Exception exception) {
                                core.getLogger().log(
                                        Level.WARNING,
                                        "Could not persist final Sell v10 shadow-learning batch",
                                        exception
                                );
                            }
                        }
                );
            } catch (RejectedExecutionException exception) {
                core.getLogger().log(
                        Level.WARNING,
                        "Sell v10 shadow-learning final persistence task was rejected",
                        exception
                );
            }
        }

        executor.shutdown();

        try {
            if (!executor.awaitTermination(
                    SHUTDOWN_BUDGET_MILLIS,
                    TimeUnit.MILLISECONDS
            )) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        pendingActivity.clear();
    }

    private synchronized void reloadConfiguration() {
        File file = new File(
                core.getDataFolder(),
                "sell.yml"
        );
        FileConfiguration sellConfig =
                YamlConfiguration.loadConfiguration(
                        file
                );

        config = LearningConfig.from(
                sellConfig
        );
        storage = new SellLearningStorage(
                core,
                sellConfig
        );
    }

    private void initializeSqlAsync() {
        if (sqlConnecting
                || closed
                || !started
                || storage == null) {
            return;
        }

        if (!storage.sqlConfigured()) {
            nextSqlRetryAt = Long.MAX_VALUE;
            core.getLogger().warning(
                    "Sell v10 shadow learning requires MySQL/MariaDB — learning is frozen"
            );
            return;
        }

        sqlConnecting = true;
        SellLearningStorage expectedStorage =
                storage;

        try {
            executor.execute(
                    () -> {
                        Map<String, LearningRow> loaded =
                                Map.of();
                        Exception failure = null;

                        try {
                            expectedStorage.initialize();
                            loaded =
                                    expectedStorage.loadLearning();
                        } catch (Exception exception) {
                            failure = exception;
                        }

                        Map<String, LearningRow> finalLoaded =
                                loaded;
                        Exception finalFailure =
                                failure;

                        dispatchMain(
                                () -> finishSqlInitialization(
                                        expectedStorage,
                                        finalLoaded,
                                        finalFailure
                                )
                        );
                    }
            );
        } catch (RejectedExecutionException exception) {
            sqlConnecting = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + SQL_RETRY_MILLIS;
        }
    }

    private synchronized void finishSqlInitialization(
            SellLearningStorage expectedStorage,
            Map<String, LearningRow> loaded,
            Exception failure
    ) {
        if (closed
                || expectedStorage != storage) {
            return;
        }

        sqlConnecting = false;

        if (failure != null) {
            sqlReady = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + SQL_RETRY_MILLIS;
            core.getLogger().log(
                    Level.WARNING,
                    "Sell v10 shadow-learning database unavailable — learning and meta rotation are frozen; live payouts are unaffected",
                    failure
            );
            return;
        }

        learningState =
                loaded == null
                        ? Map.of()
                        : Map.copyOf(loaded);
        sqlReady = true;
        nextSqlRetryAt = 0L;

        core.getLogger().info(
                "Sell v10 shadow-learning database connected — "
                        + learningState.size()
                        + " learned market state(s) loaded"
        );

        flushAsync();
    }

    private void samplePlayerTime(
            long now
    ) {
        long previous =
                lastPlayerSampleAt;
        lastPlayerSampleAt = now;

        if (previous <= 0L
                || now <= previous) {
            return;
        }

        long elapsed = Math.min(
                now - previous,
                2L * MINUTE_MILLIS
        );
        int online =
                Bukkit.getOnlinePlayers()
                        .size();

        if (online <= 0
                || elapsed <= 0L) {
            return;
        }

        long playerMillis =
                safeMultiply(
                        online,
                        elapsed
                );
        MutableActivity activity =
                pendingActivity.computeIfAbsent(
                        hourBucket(now),
                        ignored -> new MutableActivity()
                );
        activity.playerMillis =
                safeAdd(
                        activity.playerMillis,
                        playerMillis
                );
    }

    private void flushAsync() {
        if (!sqlReady
                || storage == null
                || closed) {
            return;
        }

        WriteBatch batch =
                takePendingBatch();

        if (batch.emptyBatch()) {
            return;
        }

        SellLearningStorage expectedStorage =
                storage;

        try {
            executor.execute(
                    () -> {
                        try {
                            expectedStorage.saveBatch(
                                    batch
                            );
                        } catch (Exception exception) {
                            dispatchMain(
                                    () -> handlePersistenceFailure(
                                            expectedStorage,
                                            batch,
                                            exception
                                    )
                            );
                        }
                    }
            );
        } catch (RejectedExecutionException exception) {
            requeue(batch);
        }
    }

    private void evaluateAsync(
            Map<String, MarketReference> references,
            long evaluationAt
    ) {
        if (!sqlReady
                || storage == null
                || evaluationQueued
                || references.isEmpty()
                || closed) {
            return;
        }

        evaluationQueued = true;
        WriteBatch pending =
                takePendingBatch();
        SellLearningStorage expectedStorage =
                storage;
        Map<String, LearningRow> previous =
                learningState;

        try {
            executor.execute(
                    () -> {
                        EvaluationResult result = null;
                        Exception failure = null;
                        boolean pendingPersisted =
                                pending.emptyBatch();

                        try {
                            if (!pending.emptyBatch()) {
                                expectedStorage.saveBatch(
                                        pending
                                );
                                pendingPersisted = true;
                            }

                            /*
                             * Import completed Sell payouts from the existing
                             * durable transaction ledger before evaluating the
                             * market. The cursor and aggregate rows advance in
                             * one SQL transaction, so this remains exactly-once
                             * across restarts without touching the payout path.
                             */
                            int importedSales =
                                    expectedStorage.synchronizeSellLedger(
                                            evaluationAt
                                                    - config.retentionMillis(),
                                            50_000
                                    );

                            if (importedSales >= 50_000) {
                                core.getLogger().info(
                                        "Sell v10 shadow learner imported 50,000 ledger sales this pass — additional history will continue syncing on later evaluations"
                                );
                            }

                            result = evaluate(
                                    expectedStorage,
                                    references,
                                    previous,
                                    evaluationAt
                            );

                            if (!result.rows().isEmpty()) {
                                expectedStorage.saveBatch(
                                        new WriteBatch(
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                result.rows()
                                        )
                                );
                            }

                            if (evaluationAt - lastPruneAt
                                    >= DAY_MILLIS) {
                                expectedStorage.pruneBefore(
                                        evaluationAt
                                                - config.retentionMillis()
                                );
                            }
                        } catch (Exception exception) {
                            failure = exception;
                        }

                        EvaluationResult finalResult =
                                result;
                        Exception finalFailure =
                                failure;

                        boolean finalPendingPersisted =
                                pendingPersisted;

                        dispatchMain(
                                () -> finishEvaluation(
                                        expectedStorage,
                                        pending,
                                        finalPendingPersisted,
                                        finalResult,
                                        finalFailure,
                                        evaluationAt
                                )
                        );
                    }
            );
        } catch (RejectedExecutionException exception) {
            evaluationQueued = false;
            requeue(pending);
        }
    }

    private EvaluationResult evaluate(
            SellLearningStorage currentStorage,
            Map<String, MarketReference> references,
            Map<String, LearningRow> previous,
            long now
    ) throws Exception {
        long recentStart =
                now - config.recentMillis();
        long baselineStart =
                now - config.baselineMillis();
        long baselineEnd =
                recentStart;

        WindowSnapshot recent =
                currentStorage.loadWindow(
                        recentStart,
                        now,
                        true
                );
        WindowSnapshot baseline =
                currentStorage.loadWindow(
                        baselineStart,
                        baselineEnd,
                        false
                );

        double recentPlayerHours =
                playerHours(
                        recent.activity()
                                .playerMillis()
                );
        double baselinePlayerHours =
                playerHours(
                        baseline.activity()
                                .playerMillis()
                );
        double averageOnline =
                recentPlayerHours
                        / Math.max(
                        1.0D,
                        config.recentHours()
                );

        Map<String, LearningRow> next =
                new LinkedHashMap<>();
        List<Candidate> candidates =
                new ArrayList<>();
        List<Alert> alerts =
                new ArrayList<>();
        int occupiedMetaSlots = 0;

        for (Map.Entry<String, MarketReference> referenceEntry
                : references.entrySet()) {
            String key =
                    referenceEntry.getKey();
            MarketReference reference =
                    referenceEntry.getValue();
            LearningRow old =
                    previous.get(key);
            MarketTotals recentMarket =
                    recent.markets()
                            .get(key);
            MarketTotals baselineMarket =
                    baseline.markets()
                            .get(key);

            long recentUnits =
                    recentMarket == null
                            ? 0L
                            : recentMarket.sellUnits();
            long baselineUnits =
                    baselineMarket == null
                            ? 0L
                            : baselineMarket.sellUnits();
            long baselineTransactions =
                    baselineMarket == null
                            ? 0L
                            : baselineMarket.sellTransactions();

            BigDecimal recentRate =
                    rate(
                            recentUnits,
                            recentPlayerHours
                    );
            BigDecimal baselineRate =
                    rate(
                            baselineUnits,
                            baselinePlayerHours
                    );
            double confidence =
                    confidence(
                            baselinePlayerHours,
                            baselineTransactions
                    );
            boolean evidenceReady =
                    baselinePlayerHours
                            >= config.minimumBaselinePlayerHours()
                            && recentPlayerHours
                            >= config.minimumRecentPlayerHours()
                            && baselineTransactions
                            >= config.minimumBaselineTransactions()
                            && baselineRate.signum() > 0;
            double ratio =
                    evidenceReady
                            ? ratio(
                            recentRate,
                            baselineRate
                    )
                            : 1.0D;
            double recommendation =
                    evidenceReady
                            ? recommendedMultiplier(
                            ratio,
                            confidence
                    )
                            : 1.0D;
            BigDecimal shadowReference =
                    old != null
                            && old.shadowReferenceUnitCents()
                            .signum() > 0
                            ? old.shadowReferenceUnitCents()
                            : reference.unitCents();
            String state =
                    evidenceReady
                            ? ratio >= config.oversupplyRatio()
                            ? STATE_SATURATED
                            : STATE_STABLE
                            : STATE_LEARNING;
            long metaStartedAt = 0L;
            long metaTargetUnits = 0L;
            long metaProgressUnits = 0L;
            long lastMetaAt =
                    old == null
                            ? 0L
                            : old.lastMetaAt();
            long lastAlertAt =
                    old == null
                            ? 0L
                            : old.lastAlertAt();

            if (old != null
                    && STATE_META_READY.equals(
                    old.state()
            )) {
                /*
                 * A completed meta deliberately frees its slot one evaluation
                 * later. This guarantees the completion threshold is visible
                 * as a distinct state and prevents same-tick churn.
                 */
                lastMetaAt = now;
            } else if (old != null
                    && STATE_META.equals(
                    old.state()
            )) {
                metaStartedAt =
                        old.metaStartedAt();
                metaTargetUnits =
                        Math.max(
                                1L,
                                old.metaTargetUnits()
                        );
                metaProgressUnits =
                        currentStorage.marketSellUnits(
                                key,
                                metaStartedAt,
                                now
                        );
                recommendation = Math.max(
                        recommendation,
                        1.0D
                                + config.metaMinimumBoost()
                );

                if (metaProgressUnits
                        >= metaTargetUnits) {
                    state = STATE_META_READY;
                    occupiedMetaSlots++;

                    if (!STATE_META_READY.equals(
                            old.state()
                    )) {
                        alerts.add(
                                metaCompletedAlert(
                                        reference,
                                        metaProgressUnits,
                                        metaTargetUnits,
                                        confidence
                                )
                        );
                        lastAlertAt = now;
                    }
                } else {
                    state = STATE_META;
                    occupiedMetaSlots++;
                }
            }

            LearningRow row =
                    new LearningRow(
                            key,
                            reference.material().name(),
                            shadowReference,
                            baselineRate,
                            recentRate,
                            ratio,
                            confidence,
                            recommendation,
                            state,
                            metaStartedAt,
                            metaTargetUnits,
                            metaProgressUnits,
                            lastMetaAt,
                            now,
                            lastAlertAt
                    );
            next.put(
                    key,
                    row
            );

            if (evidenceReady
                    && !STATE_META.equals(state)
                    && !STATE_META_READY.equals(state)
                    && ratio <= config.shortageRatio()
                    && confidence
                    >= config.metaMinimumConfidence()
                    && now - lastMetaAt
                    >= config.metaCooldownMillis()) {
                candidates.add(
                        new Candidate(
                                key,
                                reference,
                                shortageScore(
                                        ratio,
                                        confidence
                                ),
                                baselineRate,
                                recentRate,
                                ratio,
                                confidence
                        )
                );
            }
        }

        candidates.sort(
                Comparator.comparingDouble(
                                Candidate::score
                        )
                        .reversed()
                        .thenComparing(
                                Candidate::marketKey
                        )
        );

        int availableSlots = Math.max(
                0,
                config.metaSlots()
                        - occupiedMetaSlots
        );
        int newMetaCount = 0;

        for (Candidate candidate : candidates) {
            if (availableSlots <= 0) {
                break;
            }

            LearningRow row =
                    next.get(
                            candidate.marketKey()
                    );

            if (row == null
                    || STATE_META.equals(row.state())
                    || STATE_META_READY.equals(row.state())) {
                continue;
            }

            long target =
                    metaTargetUnits(
                            candidate.baselineRate(),
                            averageOnline
                    );
            double recommendation = Math.max(
                    row.recommendedMultiplier(),
                    1.0D
                            + config.metaMinimumBoost()
            );

            LearningRow meta =
                    new LearningRow(
                            row.marketKey(),
                            row.referenceMaterial(),
                            row.shadowReferenceUnitCents(),
                            row.baselineUnitsPerPlayerHour(),
                            row.recentUnitsPerPlayerHour(),
                            row.supplyRatio(),
                            row.confidence(),
                            recommendation,
                            STATE_META,
                            now,
                            target,
                            0L,
                            now,
                            now,
                            now
                    );
            next.put(
                    candidate.marketKey(),
                    meta
            );
            newMetaCount++;
            alerts.add(
                    newMetaAlert(
                            candidate,
                            target,
                            recommendation
                    )
            );
            availableSlots--;
        }

        concentrationAlerts(
                recent,
                references,
                now,
                alerts
        );

        return new EvaluationResult(
                List.copyOf(
                        next.values()
                ),
                List.copyOf(alerts),
                Map.copyOf(next),
                recentPlayerHours,
                baselinePlayerHours,
                newMetaCount
        );
    }

    private synchronized void finishEvaluation(
            SellLearningStorage expectedStorage,
            WriteBatch pending,
            boolean pendingPersisted,
            EvaluationResult result,
            Exception failure,
            long evaluatedAt
    ) {
        evaluationQueued = false;

        if (closed
                || expectedStorage != storage) {
            return;
        }

        if (failure != null
                || result == null) {
            if (!pendingPersisted) {
                requeue(pending);
            }
            sqlReady = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + SQL_RETRY_MILLIS;

            core.getLogger().log(
                    Level.WARNING,
                    "Sell v10 shadow evaluation failed — learning is frozen and live payouts remain unchanged",
                    failure
            );
            return;
        }

        learningState =
                result.state();
        lastPruneAt =
                evaluatedAt;

        dispatchAlerts(
                result.alerts()
        );

        core.getLogger().info(
                "Sell v10 shadow evaluation complete — "
                        + learningState.size()
                        + " market(s), "
                        + formatOneDecimal(
                        result.recentPlayerHours()
                )
                        + " recent player-hour(s), "
                        + formatOneDecimal(
                        result.baselinePlayerHours()
                )
                        + " baseline player-hour(s), "
                        + result.newMetaCount()
                        + " new shadow meta(s)"
        );
    }

    private synchronized void handlePersistenceFailure(
            SellLearningStorage expectedStorage,
            WriteBatch batch,
            Exception exception
    ) {
        if (expectedStorage != storage
                || closed) {
            return;
        }

        requeue(batch);
        sqlReady = false;
        nextSqlRetryAt =
                System.currentTimeMillis()
                        + SQL_RETRY_MILLIS;

        core.getLogger().log(
                Level.WARNING,
                "Sell v10 shadow telemetry persistence failed — learning is frozen and live payouts remain unchanged",
                exception
        );
    }

    private Map<String, MarketReference> captureReferences() {
        Map<String, MarketReference> references =
                new HashMap<>();

        for (Material material
                : sellService.worthCatalogMaterials()) {
            if (material == null
                    || !sellService.isServerSellableMaterial(
                    material
            )) {
                continue;
            }

            long payout =
                    sellService.serverUnitSellCents(
                            (UUID) null,
                            material
                    );

            if (payout <= 0L) {
                continue;
            }

            String marketKey =
                    normalizeKey(
                            sellService.marketKey(
                                    material
                            )
                    );
            long units =
                    sellService.marketUnits(
                            material
                    );

            if (marketKey.isBlank()
                    || units <= 0L) {
                continue;
            }

            BigDecimal unitCents =
                    BigDecimal.valueOf(payout)
                            .divide(
                                    BigDecimal.valueOf(units),
                                    8,
                                    RoundingMode.HALF_UP
                            );
            MarketReference current =
                    references.get(
                            marketKey
                    );

            if (current == null
                    || unitCents.compareTo(
                    current.unitCents()
            ) < 0) {
                references.put(
                        marketKey,
                        new MarketReference(
                                marketKey,
                                material,
                                unitCents
                        )
                );
            }
        }

        return Map.copyOf(references);
    }

    private void concentrationAlerts(
            WindowSnapshot recent,
            Map<String, MarketReference> references,
            long now,
            List<Alert> alerts
    ) {
        if (!config.concentrationAlertsEnabled()
                || recent.sellers().isEmpty()) {
            return;
        }

        Map<String, SellerTotals> leaders =
                new HashMap<>();

        for (SellerTotals seller
                : recent.sellers()) {
            SellerTotals current =
                    leaders.get(
                            seller.marketKey()
                    );

            if (current == null
                    || seller.sellUnits()
                    > current.sellUnits()) {
                leaders.put(
                        seller.marketKey(),
                        seller
                );
            }
        }

        for (Map.Entry<String, SellerTotals> entry
                : leaders.entrySet()) {
            String key =
                    entry.getKey();
            MarketTotals market =
                    recent.markets()
                            .get(key);
            SellerTotals seller =
                    entry.getValue();

            if (market == null
                    || market.sellUnits() <= 0L
                    || market.sellTransactions()
                    < config.concentrationMinimumTransactions()) {
                continue;
            }

            double share =
                    (double) seller.sellUnits()
                            / (double) market.sellUnits();

            if (share
                    < config.concentrationShare()) {
                continue;
            }

            long previousAlert =
                    concentrationAlertAt.getOrDefault(
                            key,
                            0L
                    );

            if (now - previousAlert
                    < config.concentrationAlertCooldownMillis()) {
                continue;
            }

            concentrationAlertAt.put(
                    key,
                    now
            );
            MarketReference reference =
                    references.get(key);
            String item =
                    reference == null
                            ? key
                            : pretty(
                            reference.material()
                    );
            String sellerName =
                    seller.playerName().isBlank()
                            ? shortUuid(
                            seller.playerId()
                    )
                            : seller.playerName();

            alerts.add(
                    new Alert(
                            "&#8436FEMineacle Sell &#bbbbbb» High seller concentration",
                            "&#D0AFFF"
                                    + sellerName
                                    + " &#bbbbbbprovided "
                                    + percent(share)
                                    + " of "
                                    + item
                                    + " server-sell volume over the last "
                                    + config.recentHoursLabel()
                                    + " • "
                                    + seller.sellUnits()
                                    + " normalized units • &a"
                                    + sellService.format(
                                    seller.sellPayoutCents()
                            )
                    )
            );
        }
    }

    private Alert newMetaAlert(
            Candidate candidate,
            long target,
            double recommendation
    ) {
        return new Alert(
                "&#8436FEMineacle Sell &#bbbbbb» New shadow meta: &#D0AFFF"
                        + pretty(
                        candidate.reference()
                                .material()
                ),
                "&#bbbbbbSupply is "
                        + percent(
                        candidate.ratio()
                )
                        + " of learned baseline • confidence "
                        + percent(
                        candidate.confidence()
                )
                        + " • completion threshold "
                        + target
                        + " normalized units • shadow recommendation "
                        + multiplier(
                        recommendation
                )
        );
    }

    private Alert metaCompletedAlert(
            MarketReference reference,
            long progress,
            long target,
            double confidence
    ) {
        return new Alert(
                "&#8436FEMineacle Sell &#bbbbbb» Shadow meta threshold reached: &#D0AFFF"
                        + pretty(
                        reference.material()
                ),
                "&#bbbbbb"
                        + progress
                        + "/"
                        + target
                        + " normalized units • confidence "
                        + percent(confidence)
                        + " • eligible to rotate on the next evaluation"
        );
    }

    private void dispatchAlerts(
            List<Alert> alerts
    ) {
        if (alerts == null
                || alerts.isEmpty()) {
            return;
        }

        for (Alert alert : alerts) {
            core.getLogger().info(
                    stripColors(
                            alert.title()
                                    + " | "
                                    + alert.detail()
                    )
            );

            for (Player player
                    : Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission(
                        ALERT_PERMISSION
                )) {
                    continue;
                }

                player.sendMessage(
                        TextColor.color(
                                alert.title()
                        )
                );
                player.sendMessage(
                        TextColor.color(
                                alert.detail()
                        )
                );
            }
        }
    }

    private double confidence(
            double baselinePlayerHours,
            long baselineTransactions
    ) {
        double timeEvidence = Math.clamp(
                baselinePlayerHours
                        / config.fullConfidencePlayerHours(),
                0.0D,
                1.0D
        );
        double transactionEvidence = Math.clamp(
                (double) baselineTransactions
                        / (double) config.fullConfidenceTransactions(),
                0.0D,
                1.0D
        );

        return Math.sqrt(
                timeEvidence
                        * transactionEvidence
        );
    }

    private double recommendedMultiplier(
            double supplyRatio,
            double confidence
    ) {
        double maximumMove =
                config.maximumRecommendationMove();

        if (supplyRatio < 1.0D) {
            double shortage = Math.clamp(
                    1.0D - supplyRatio,
                    0.0D,
                    1.0D
            );
            return 1.0D
                    + (maximumMove
                    * shortage
                    * confidence);
        }

        double oversupplySpan = Math.max(
                0.01D,
                config.oversupplyRatio()
                        - 1.0D
        );
        double oversupply = Math.clamp(
                (supplyRatio - 1.0D)
                        / oversupplySpan,
                0.0D,
                1.0D
        );

        return Math.max(
                config.minimumRecommendationMultiplier(),
                1.0D
                        - (maximumMove
                        * oversupply
                        * confidence)
        );
    }

    private double shortageScore(
            double supplyRatio,
            double confidence
    ) {
        double shortage = Math.clamp(
                (config.shortageRatio()
                        - supplyRatio)
                        / Math.max(
                        0.01D,
                        config.shortageRatio()
                ),
                0.0D,
                1.0D
        );

        return confidence
                * (0.25D + shortage);
    }

    private long metaTargetUnits(
            BigDecimal baselineRate,
            double averageOnline
    ) {
        if (baselineRate == null
                || baselineRate.signum() <= 0) {
            return 1L;
        }

        try {
            BigDecimal target =
                    baselineRate
                            .multiply(
                                    BigDecimal.valueOf(
                                            Math.max(
                                                    0.25D,
                                                    averageOnline
                                            )
                                    )
                            )
                            .multiply(
                                    BigDecimal.valueOf(
                                            config.metaTargetHours()
                                    )
                            )
                            .multiply(
                                    BigDecimal.valueOf(
                                            config.metaCompletionFactor()
                                    )
                            )
                            .setScale(
                                    0,
                                    RoundingMode.CEILING
                            )
                            .max(
                                    BigDecimal.ONE
                            );

            return target.longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private synchronized WriteBatch takePendingBatch() {
        if (pendingActivity.isEmpty()) {
            return WriteBatch.empty();
        }

        List<ActivityDelta> activity =
                new ArrayList<>(
                        pendingActivity.size()
                );

        for (Map.Entry<Long, MutableActivity> entry
                : pendingActivity.entrySet()) {
            activity.add(
                    new ActivityDelta(
                            entry.getKey(),
                            entry.getValue().playerMillis,
                            0L,
                            0L,
                            0L,
                            0L,
                            0L,
                            0L
                    )
            );
        }

        pendingActivity.clear();

        return new WriteBatch(
                List.copyOf(activity),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private synchronized void requeue(
            WriteBatch batch
    ) {
        if (batch == null
                || batch.emptyBatch()
                || closed) {
            return;
        }

        for (ActivityDelta row : batch.activity()) {
            MutableActivity target =
                    pendingActivity.computeIfAbsent(
                            row.bucketStart(),
                            ignored -> new MutableActivity()
                    );
            target.playerMillis =
                    safeAdd(
                            target.playerMillis,
                            row.playerMillis()
                    );
        }
    }

    private void trimPending(
            long now
    ) {
        long cutoff =
                hourBucket(
                        now - config.pendingRetentionMillis()
                );
        pendingActivity.keySet()
                .removeIf(
                        bucket -> bucket < cutoff
                );
    }

    private BigDecimal rate(
            long units,
            double playerHours
    ) {
        if (units <= 0L
                || !Double.isFinite(playerHours)
                || playerHours <= 0.0D) {
            return BigDecimal.ZERO;
        }

        return BigDecimal.valueOf(units)
                .divide(
                        BigDecimal.valueOf(playerHours),
                        8,
                        RoundingMode.HALF_UP
                );
    }

    private double ratio(
            BigDecimal recent,
            BigDecimal baseline
    ) {
        if (baseline == null
                || baseline.signum() <= 0) {
            return 1.0D;
        }

        try {
            double value =
                    recent.divide(
                                    baseline,
                                    8,
                                    RoundingMode.HALF_UP
                            )
                            .doubleValue();

            return Double.isFinite(value)
                    && value >= 0.0D
                    ? value
                    : 1.0D;
        } catch (ArithmeticException exception) {
            return 1.0D;
        }
    }

    private double playerHours(
            long playerMillis
    ) {
        return Math.max(
                0.0D,
                (double) playerMillis
                        / (double) HOUR_MILLIS
        );
    }

    private String pretty(
            Material material
    ) {
        if (material == null) {
            return "Unknown Item";
        }

        return sellService.pretty(material);
    }

    private String normalizeKey(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "";
        }

        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private long hourBucket(
            long timestamp
    ) {
        long safe = Math.max(
                0L,
                timestamp
        );
        return safe
                - (safe % HOUR_MILLIS);
    }

    private long safeAdd(
            long first,
            long second
    ) {
        if (first < 0L
                || second < 0L) {
            return Long.MAX_VALUE;
        }

        if (Long.MAX_VALUE - first
                < second) {
            return Long.MAX_VALUE;
        }

        return first + second;
    }

    private long safeMultiply(
            long first,
            long second
    ) {
        if (first <= 0L
                || second <= 0L) {
            return 0L;
        }

        if (first > Long.MAX_VALUE
                / second) {
            return Long.MAX_VALUE;
        }

        return first * second;
    }

    private void dispatchMain(
            Runnable task
    ) {
        if (task == null
                || closed
                || !core.isEnabled()) {
            return;
        }

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        task
                );
    }

    private String percent(
            double fraction
    ) {
        return String.format(
                Locale.US,
                "%.0f%%",
                Math.max(
                        0.0D,
                        fraction
                ) * 100.0D
        );
    }

    private String multiplier(
            double value
    ) {
        return String.format(
                Locale.US,
                "%.3fx",
                Math.max(
                        0.0D,
                        value
                )
        );
    }

    private String formatOneDecimal(
            double value
    ) {
        return String.format(
                Locale.US,
                "%.1f",
                Math.max(
                        0.0D,
                        value
                )
        );
    }

    private String shortUuid(
            UUID playerId
    ) {
        if (playerId == null) {
            return "Unknown";
        }

        String raw =
                playerId.toString();
        return raw.substring(
                0,
                Math.min(
                        8,
                        raw.length()
                )
        );
    }

    private String stripColors(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value.replaceAll(
                        "(?i)&#[0-9a-f]{6}",
                        ""
                )
                .replaceAll(
                        "(?i)&[0-9a-fk-or]",
                        ""
                );
    }

    private record MarketReference(
            String marketKey,
            Material material,
            BigDecimal unitCents
    ) {
    }

    private record Candidate(
            String marketKey,
            MarketReference reference,
            double score,
            BigDecimal baselineRate,
            BigDecimal recentRate,
            double ratio,
            double confidence
    ) {
    }

    private record Alert(
            String title,
            String detail
    ) {
    }

    private record EvaluationResult(
            List<LearningRow> rows,
            List<Alert> alerts,
            Map<String, LearningRow> state,
            double recentPlayerHours,
            double baselinePlayerHours,
            int newMetaCount
    ) {
    }

    private static final class MutableActivity {
        private long playerMillis;
    }

    private record LearningConfig(
            boolean enabled,
            long flushMillis,
            long evaluationMillis,
            double recentHours,
            long recentMillis,
            long baselineMillis,
            long retentionMillis,
            long pendingRetentionMillis,
            double minimumRecentPlayerHours,
            double minimumBaselinePlayerHours,
            double fullConfidencePlayerHours,
            long minimumBaselineTransactions,
            long fullConfidenceTransactions,
            double shortageRatio,
            double oversupplyRatio,
            double maximumRecommendationMove,
            double minimumRecommendationMultiplier,
            int metaSlots,
            double metaMinimumConfidence,
            double metaMinimumBoost,
            double metaTargetHours,
            double metaCompletionFactor,
            long metaCooldownMillis,
            boolean concentrationAlertsEnabled,
            long concentrationMinimumTransactions,
            double concentrationShare,
            long concentrationAlertCooldownMillis
    ) {
        private static LearningConfig from(
                FileConfiguration config
        ) {
            int recentHours = Math.clamp(
                    config.getInt(
                            "learning.recent-hours",
                            6
                    ),
                    1,
                    24
            );
            int baselineDays = Math.clamp(
                    config.getInt(
                            "learning.baseline-days",
                            7
                    ),
                    2,
                    30
            );
            int retentionDays = Math.clamp(
                    config.getInt(
                            "learning.retention-days",
                            30
                    ),
                    baselineDays + 1,
                    180
            );
            int pendingHours = Math.clamp(
                    config.getInt(
                            "learning.database-outage-buffer-hours",
                            72
                    ),
                    6,
                    168
            );
            double maximumMove = Math.clamp(
                    config.getDouble(
                            "learning.shadow.maximum-recommendation-change-percent",
                            20.0D
                    ) / 100.0D,
                    0.01D,
                    0.50D
            );
            double shortage = Math.clamp(
                    config.getDouble(
                            "learning.meta.shortage-ratio",
                            0.65D
                    ),
                    0.05D,
                    0.95D
            );
            double oversupply = Math.clamp(
                    config.getDouble(
                            "learning.oversupply-ratio",
                            1.50D
                    ),
                    1.05D,
                    10.0D
            );

            return new LearningConfig(
                    config.getBoolean(
                            "learning.enabled",
                            true
                    ),
                    Math.clamp(
                            config.getLong(
                                    "learning.flush-seconds",
                                    30L
                            ),
                            10L,
                            300L
                    ) * 1_000L,
                    Math.clamp(
                            config.getLong(
                                    "learning.evaluation-minutes",
                                    15L
                            ),
                            5L,
                            60L
                    ) * MINUTE_MILLIS,
                    recentHours,
                    recentHours * HOUR_MILLIS,
                    baselineDays * DAY_MILLIS,
                    retentionDays * DAY_MILLIS,
                    pendingHours * HOUR_MILLIS,
                    Math.max(
                            1.0D,
                            config.getDouble(
                                    "learning.evidence.minimum-recent-player-hours",
                                    8.0D
                            )
                    ),
                    Math.max(
                            1.0D,
                            config.getDouble(
                                    "learning.evidence.minimum-baseline-player-hours",
                                    24.0D
                            )
                    ),
                    Math.max(
                            1.0D,
                            config.getDouble(
                                    "learning.evidence.full-confidence-player-hours",
                                    168.0D
                            )
                    ),
                    Math.max(
                            1L,
                            config.getLong(
                                    "learning.evidence.minimum-baseline-transactions",
                                    4L
                            )
                    ),
                    Math.max(
                            1L,
                            config.getLong(
                                    "learning.evidence.full-confidence-transactions",
                                    32L
                            )
                    ),
                    shortage,
                    oversupply,
                    maximumMove,
                    Math.clamp(
                            config.getDouble(
                                    "learning.shadow.minimum-recommended-multiplier",
                                    0.70D
                            ),
                            0.10D,
                            1.0D
                    ),
                    Math.clamp(
                            config.getInt(
                                    "learning.meta.slots",
                                    6
                            ),
                            1,
                            20
                    ),
                    Math.clamp(
                            config.getDouble(
                                    "learning.meta.minimum-confidence",
                                    0.75D
                            ),
                            0.10D,
                            1.0D
                    ),
                    Math.clamp(
                            config.getDouble(
                                    "learning.meta.minimum-shadow-boost-percent",
                                    15.0D
                            ) / 100.0D,
                            0.01D,
                            1.0D
                    ),
                    Math.clamp(
                            config.getDouble(
                                    "learning.meta.target-hours",
                                    6.0D
                            ),
                            1.0D,
                            48.0D
                    ),
                    Math.clamp(
                            config.getDouble(
                                    "learning.meta.completion-factor",
                                    1.25D
                            ),
                            0.25D,
                            10.0D
                    ),
                    Math.clamp(
                            config.getLong(
                                    "learning.meta.cooldown-hours",
                                    24L
                            ),
                            0L,
                            168L
                    ) * HOUR_MILLIS,
                    config.getBoolean(
                            "learning.alerts.seller-concentration.enabled",
                            true
                    ),
                    Math.max(
                            2L,
                            config.getLong(
                                    "learning.alerts.seller-concentration.minimum-transactions",
                                    8L
                            )
                    ),
                    Math.clamp(
                            config.getDouble(
                                    "learning.alerts.seller-concentration.share",
                                    0.75D
                            ),
                            0.50D,
                            1.0D
                    ),
                    Math.clamp(
                            config.getLong(
                                    "learning.alerts.seller-concentration.cooldown-hours",
                                    6L
                            ),
                            1L,
                            168L
                    ) * HOUR_MILLIS
            );
        }

        private String recentHoursLabel() {
            long rounded = Math.max(
                    1L,
                    Math.round(recentHours)
            );
            return rounded
                    + (rounded == 1L
                    ? " hour"
                    : " hours");
        }
    }
}
