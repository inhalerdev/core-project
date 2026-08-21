package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import net.mineacle.core.sell.model.SellCatalogEntry;
import net.mineacle.core.sell.model.SellCatalogSnapshot;
import net.mineacle.core.sell.service.SellCatalogV10Compiler.LiveAuthority;
import net.mineacle.core.sell.service.SellCatalogV10Compiler.LiveCompilation;
import net.mineacle.core.sell.storage.SellLearningStorage.LearningRow;
import net.mineacle.core.sell.storage.SellLivePricingStorage;
import net.mineacle.core.sell.storage.SellLivePricingStorage.Generation;
import net.mineacle.core.sell.storage.SellLivePricingStorage.GenerationDraft;
import net.mineacle.core.sell.storage.SellLivePricingStorage.PricePoint;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Evidence-gated live pricing authority for Sell/Worth revision 10.
 *
 * <p>The learner never writes prices. It supplies an immutable evidence
 * snapshot to this service. The governor computes bounded market multipliers
 * from the frozen v10 reference catalog, applies a one-way global issuance
 * brake based on Sell dollars issued per active-player-hour, stages the price
 * book durably, recompiles the entire catalog through the v10 recipe solver,
 * and only then swaps the runtime catalog atomically.</p>
 *
 * <p>Database failure freezes new generations. The currently active validated
 * runtime snapshot remains untouched. A small local active-generation cache is
 * atomically promoted alongside runtime activation so a restart can restore the
 * last validated generation even while SQL is unavailable.</p>
 */
public final class SellLivePricingService {

    private static final int CACHE_VERSION = 1;

    private static final long MINUTE_MILLIS =
            60_000L;
    private static final long DAY_MILLIS =
            24L * 60L * MINUTE_MILLIS;
    private static final long SHUTDOWN_BUDGET_MILLIS =
            5_000L;

    private final Core core;
    private final SellService sellService;
    private final ScheduledThreadPoolExecutor executor;

    private final File activeCacheFile;
    private final File stagedCacheFile;
    private final SellLivePricingStorage storage;
    private final LiveConfig config;

    private volatile ReferenceState referenceState;
    private volatile Map<String, ReferenceMarket>
            referenceMarkets =
            Map.of();

    private Generation activeGeneration;

    private boolean started;
    private boolean closed;
    private boolean restoring;
    private boolean restoreComplete;
    private boolean sqlReady;
    private boolean sqlConnecting;
    private boolean publishQueued;
    private long nextSqlRetryAt;
    private long pendingPromotionId;

    public SellLivePricingService(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;

        FileConfiguration sellConfig =
                YamlConfiguration.loadConfiguration(
                        new File(
                                core.getDataFolder(),
                                "sell.yml"
                        )
                );

        this.config =
                LiveConfig.from(
                        sellConfig
                );
        this.storage =
                new SellLivePricingStorage(
                        core,
                        sellConfig
                );
        this.activeCacheFile =
                new File(
                        core.getDataFolder(),
                        "sell-v10-live.yml"
                );
        this.stagedCacheFile =
                new File(
                        core.getDataFolder(),
                        "sell-v10-live.staged.yml"
                );

        this.executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "Mineacle-SellLivePricing"
                                    );
                            thread.setDaemon(true);
                            return thread;
                        }
                );
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public synchronized void start() {
        if (started
                || closed) {
            return;
        }

        started = true;

        if (!config.enabled()) {
            core.getLogger().info(
                    "Sell v10 live pricing governor disabled — static reference catalog remains authoritative"
            );
            return;
        }

        core.getLogger().info(
                "Sell v10 live pricing governor armed — evidence-gated, "
                        + percent(
                        config.maximumStep()
                )
                        + " maximum movement per evaluation, "
                        + multiplier(
                        config.minimumMultiplier()
                )
                        + "–"
                        + multiplier(
                        config.maximumMultiplier()
                )
                        + " live bounds"
        );
    }

    /**
     * Installs the frozen reference snapshot and immutable recipe authority.
     * Called on the server thread immediately after the reference catalog
     * activates successfully.
     */
    public synchronized void installReference(
            SellCatalogSnapshot referenceSnapshot,
            LiveAuthority authority
    ) {
        if (closed
                || !started
                || referenceSnapshot == null
                || authority == null
                || referenceSnapshot.revision()
                != SellPricingPolicy.CATALOG_REVISION) {
            return;
        }

        String fingerprint =
                fingerprint(
                        referenceSnapshot
                );
        Map<String, ReferenceMarket> markets =
                buildReferenceMarkets(
                        referenceSnapshot
                );
        Set<String> eligible =
                new HashSet<>();

        for (ReferenceMarket market
                : markets.values()) {
            if (market.dynamicEligible()) {
                eligible.add(
                        market.marketKey()
                );
            }
        }

        ReferenceState next =
                new ReferenceState(
                        referenceSnapshot,
                        authority,
                        fingerprint,
                        markets,
                        Set.copyOf(eligible)
                );

        referenceState = next;
        referenceMarkets =
                next.markets();
        activeGeneration = null;
        pendingPromotionId = 0L;
        restoreComplete =
                !config.enabled();
        sqlReady = false;
        nextSqlRetryAt = 0L;

        try {
            Files.deleteIfExists(
                    stagedCacheFile.toPath()
            );
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not clear stale Sell v10 staged price cache",
                    exception
            );
        }

        core.getLogger().info(
                "Sell v10 live reference authority installed — "
                        + eligible.size()
                        + " dynamic market(s), fingerprint "
                        + fingerprint.substring(
                        0,
                        Math.min(
                                12,
                                fingerprint.length()
                        )
                )
        );

        if (config.enabled()) {
            restoreAsync(next);
        }
    }

    public Map<String, ReferenceMarket>
    referenceMarkets() {
        return referenceMarkets;
    }

    /**
     * Main-thread heartbeat used only for SQL recovery. New pricing never
     * publishes while SQL is unavailable.
     */
    public synchronized void tick() {
        if (!started
                || closed
                || !config.enabled()
                || referenceState == null
                || sqlReady
                || sqlConnecting
                || restoring
                || System.currentTimeMillis()
                < nextSqlRetryAt) {
            return;
        }

        initializeSqlAsync();
    }

    public synchronized void acceptEvaluation(
            SellLearningService.EvaluationSnapshot snapshot
    ) {
        if (snapshot == null
                || !started
                || closed
                || !config.enabled()
                || !restoreComplete
                || !sqlReady
                || publishQueued
                || referenceState == null) {
            return;
        }

        publishQueued = true;

        ReferenceState expected =
                referenceState;
        Generation previous =
                activeGeneration;

        try {
            executor.execute(
                    () -> preparePublication(
                            expected,
                            previous,
                            snapshot
                    )
            );
        } catch (RejectedExecutionException exception) {
            publishQueued = false;
        }
    }

    public synchronized void shutdown() {
        if (closed) {
            return;
        }

        closed = true;
        started = false;
        restoreComplete = false;
        publishQueued = false;
        sqlReady = false;

        executor.shutdown();

        try {
            if (!executor.awaitTermination(
                    SHUTDOWN_BUDGET_MILLIS,
                    TimeUnit.MILLISECONDS
            )) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            executor.shutdownNow();
        }
    }

    private void restoreAsync(
            ReferenceState expected
    ) {
        if (restoring
                || closed) {
            return;
        }

        restoring = true;

        try {
            executor.execute(
                    () -> {
                        Generation local = null;
                        Generation database = null;
                        Exception localFailure = null;
                        Exception databaseFailure = null;
                        boolean databaseReady = false;

                        try {
                            local =
                                    loadLocalGeneration();
                        } catch (Exception exception) {
                            localFailure =
                                    exception;
                        }

                        if (storage.sqlConfigured()) {
                            try {
                                storage.initialize();
                                database =
                                        storage
                                                .loadLatestActive();
                                databaseReady = true;
                            } catch (Exception exception) {
                                databaseFailure =
                                        exception;
                            }
                        }

                        Generation validLocal =
                                validGeneration(
                                        expected,
                                        local
                                )
                                        ? local
                                        : null;
                        Generation validDatabase =
                                validGeneration(
                                        expected,
                                        database
                                )
                                        ? database
                                        : null;
                        Generation selected;
                        String source;

                        if (validDatabase != null
                                && (validLocal == null
                                || validDatabase
                                .generationId()
                                >= validLocal
                                .generationId())) {
                            selected =
                                    validDatabase;
                            source = "SQL";
                        } else if (validLocal != null) {
                            selected =
                                    validLocal;
                            source = "local cache";
                        } else {
                            selected = null;
                            source = "";
                        }

                        LiveCompilation compilation =
                                selected == null
                                        ? null
                                        : expected
                                        .authority()
                                        .reprice(
                                                selected
                                                        .liveMultipliers()
                                        );

                        RestoreResult result =
                                new RestoreResult(
                                        expected,
                                        selected,
                                        compilation,
                                        source,
                                        databaseReady,
                                        databaseFailure,
                                        localFailure,
                                        validDatabase == null
                                                ? 0L
                                                : validDatabase
                                                .generationId()
                                );

                        dispatchMain(
                                () -> finishRestore(
                                        result
                                )
                        );
                    }
            );
        } catch (RejectedExecutionException exception) {
            restoring = false;
        }
    }

    private synchronized void finishRestore(
            RestoreResult result
    ) {
        restoring = false;

        if (closed
                || result == null
                || result.reference()
                != referenceState) {
            return;
        }

        sqlReady =
                result.databaseReady();

        if (!sqlReady) {
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + config.databaseRetryMillis();
        } else {
            nextSqlRetryAt = 0L;
        }

        if (result.localFailure() != null) {
            core.getLogger().log(
                    Level.WARNING,
                    "Sell v10 local live-price cache could not be read — ignoring it",
                    result.localFailure()
            );
        }

        if (result.databaseFailure() != null) {
            core.getLogger().log(
                    Level.WARNING,
                    "Sell v10 live-price database unavailable — new repricing is frozen; local validated prices may still be restored",
                    result.databaseFailure()
            );
        }

        Generation selected =
                result.selected();
        LiveCompilation compilation =
                result.compilation();

        if (selected != null) {
            if (compilation == null
                    || !compilation.ready()) {
                core.getLogger().severe(
                        "Sell v10 stored live generation "
                                + selected.generationId()
                                + " failed current recipe validation — static reference remains active"
                                + (compilation == null
                                || compilation.failures()
                                .isEmpty()
                                ? ""
                                : " — "
                                + String.join(
                                "; ",
                                compilation.failures()
                        ))
                );
            } else if (sellService
                    .activateCatalogSnapshot(
                            compilation.snapshot()
                    )) {
                activeGeneration =
                        withActivatedAt(
                                selected,
                                System.currentTimeMillis()
                        );

                core.getLogger().info(
                        "Sell v10 live generation "
                                + selected.generationId()
                                + " restored from "
                                + result.source()
                                + " — "
                                + selected.prices().size()
                                + " market price point(s)"
                );

                queueActiveCacheWrite(
                        activeGeneration
                );

                if (sqlReady
                        && selected.generationId()
                        > result.databaseGenerationId()) {
                    pendingPromotionId =
                            selected.generationId();
                    initializeSqlAsync();
                }
            } else {
                core.getLogger().severe(
                        "Sell v10 stored live generation activation was rejected — static reference remains active"
                );
            }
        } else {
            core.getLogger().info(
                    "Sell v10 live governor found no compatible prior generation — static reference remains active until sufficient evidence publishes one"
            );
        }

        restoreComplete = true;
    }

    private void preparePublication(
            ReferenceState expected,
            Generation previous,
            SellLearningService.EvaluationSnapshot snapshot
    ) {
        try {
            PricingPlan plan =
                    pricingPlan(
                            expected,
                            previous,
                            snapshot
                    );

            if (!plan.changed()) {
                dispatchMain(
                        this::finishNoChange
                );
                return;
            }

            LiveCompilation compilation =
                    expected
                            .authority()
                            .reprice(
                                    plan.requestedMultipliers()
                            );

            if (!compilation.ready()) {
                dispatchMain(
                        () -> finishCandidateRejected(
                                compilation.failures()
                        )
                );
                return;
            }

            Map<String, PricePoint> points =
                    pricePoints(
                            expected,
                            snapshot,
                            plan,
                            compilation
                    );
            GenerationDraft draft =
                    new GenerationDraft(
                            SellPricingPolicy
                                    .CATALOG_REVISION,
                            expected.fingerprint(),
                            plan.macroMultiplier(),
                            plan.recentIssuance(),
                            plan.baselineIssuance(),
                            snapshot.evaluatedAt(),
                            points
                    );

            long generationId =
                    storage.stageGeneration(
                            draft
                    );
            long now =
                    System.currentTimeMillis();
            Generation generation =
                    new Generation(
                            generationId,
                            draft.catalogRevision(),
                            draft.referenceFingerprint(),
                            draft.macroMultiplier(),
                            draft.recentIssuanceCentsPerPlayerHour(),
                            draft.baselineIssuanceCentsPerPlayerHour(),
                            draft.evaluatedAt(),
                            now,
                            0L,
                            points
                    );

            writeGeneration(
                    stagedCacheFile,
                    generation
            );

            dispatchMain(
                    () -> activateStaged(
                            expected,
                            generation,
                            compilation.snapshot(),
                            plan.changedMarkets()
                    )
            );
        } catch (Exception exception) {
            dispatchMain(
                    () -> finishPublicationFailure(
                            exception
                    )
            );
        }
    }

    private synchronized void activateStaged(
            ReferenceState expected,
            Generation generation,
            SellCatalogSnapshot candidate,
            int changedMarkets
    ) {
        if (closed
                || expected != referenceState
                || generation == null
                || candidate == null) {
            rejectStaged(
                    generation == null
                            ? 0L
                            : generation.generationId()
            );
            publishQueued = false;
            return;
        }

        if (!sellService
                .activateCatalogSnapshot(
                        candidate
                )) {
            rejectStaged(
                    generation.generationId()
            );
            deleteStagedCache();
            publishQueued = false;

            core.getLogger().severe(
                    "Sell v10 live generation "
                            + generation.generationId()
                            + " passed compiler validation but runtime activation was rejected"
            );
            return;
        }

        long now =
                System.currentTimeMillis();
        activeGeneration =
                withActivatedAt(
                        generation,
                        now
                );
        pendingPromotionId =
                generation.generationId();

        try {
            promoteStagedCache();
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Sell v10 live generation activated but local cache promotion failed",
                    exception
            );
        }

        core.getLogger().info(
                "Sell v10 live generation "
                        + generation.generationId()
                        + " activated — catalog generation "
                        + sellService.catalogGeneration()
                        + ", "
                        + changedMarkets
                        + " market signal(s) moved, macro "
                        + multiplier(
                        generation.macroMultiplier()
                )
                        + ", issuance "
                        + moneyRate(
                        generation
                                .recentIssuanceCentsPerPlayerHour()
                )
                        + "/player-hour recent vs "
                        + moneyRate(
                        generation
                                .baselineIssuanceCentsPerPlayerHour()
                )
                        + "/player-hour baseline"
        );

        try {
            executor.execute(
                    () -> {
                        Exception failure = null;

                        try {
                            storage.activateGeneration(
                                    generation
                                            .generationId()
                            );
                            storage.pruneBefore(
                                    System.currentTimeMillis()
                                            - config
                                            .generationRetentionMillis()
                            );
                        } catch (Exception exception) {
                            failure =
                                    exception;
                        }

                        Exception finalFailure =
                                failure;

                        dispatchMain(
                                () -> finishPromotion(
                                        generation
                                                .generationId(),
                                        finalFailure
                                )
                        );
                    }
            );
        } catch (RejectedExecutionException exception) {
            sqlReady = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + config.databaseRetryMillis();
            publishQueued = false;
        }
    }

    private synchronized void finishPromotion(
            long generationId,
            Exception failure
    ) {
        if (closed) {
            return;
        }

        publishQueued = false;

        if (failure == null) {
            if (pendingPromotionId
                    == generationId) {
                pendingPromotionId = 0L;
            }
            sqlReady = true;
            nextSqlRetryAt = 0L;
            return;
        }

        sqlReady = false;
        nextSqlRetryAt =
                System.currentTimeMillis()
                        + config.databaseRetryMillis();

        core.getLogger().log(
                Level.WARNING,
                "Sell v10 live generation "
                        + generationId
                        + " is active in runtime/local cache but SQL promotion failed — new repricing is frozen until recovery",
                failure
        );
    }

    private synchronized void finishNoChange() {
        publishQueued = false;
    }

    private synchronized void finishCandidateRejected(
            List<String> failures
    ) {
        publishQueued = false;

        core.getLogger().severe(
                "Sell v10 live candidate rejected before publication — "
                        + String.join(
                        "; ",
                        failures == null
                                ? List.of(
                                "unknown validation failure"
                        )
                                : failures
                )
        );
    }

    private synchronized void finishPublicationFailure(
            Exception exception
    ) {
        publishQueued = false;
        sqlReady = false;
        nextSqlRetryAt =
                System.currentTimeMillis()
                        + config.databaseRetryMillis();

        deleteStagedCache();

        core.getLogger().log(
                Level.WARNING,
                "Sell v10 live generation could not be staged — current validated prices remain active and repricing is frozen",
                exception
        );
    }

    private PricingPlan pricingPlan(
            ReferenceState reference,
            Generation previous,
            SellLearningService.EvaluationSnapshot snapshot
    ) {
        Map<String, LearningRow> learning =
                snapshot.markets();
        BigDecimal recentIssuance =
                issuancePerPlayerHour(
                        snapshot
                                .recentSellPayoutCents(),
                        snapshot
                                .recentPlayerHours()
                );
        BigDecimal baselineIssuance =
                issuancePerPlayerHour(
                        snapshot
                                .baselineSellPayoutCents(),
                        snapshot
                                .baselinePlayerHours()
                );
        double macro =
                macroMultiplier(
                        snapshot,
                        recentIssuance,
                        baselineIssuance
                );

        Map<String, Double> requested =
                new LinkedHashMap<>();
        Map<String, Double> targets =
                new LinkedHashMap<>();
        int changedMarkets = 0;
        double maximumObservedChange = 0.0D;
        int activeRecentIssuers = 0;

        for (String key
                : reference.dynamicMarketKeys()) {
            if (snapshot
                    .recentMarketSellPayoutCents()
                    .getOrDefault(
                            key,
                            0L
                    ) > 0L) {
                activeRecentIssuers++;
            }
        }

        for (String key
                : reference.dynamicMarketKeys()) {
            LearningRow row =
                    learning.get(key);
            PricePoint oldPoint =
                    previous == null
                            ? null
                            : previous
                            .prices()
                            .get(key);
            double oldMultiplier =
                    oldPoint == null
                            ? 1.0D
                            : validMultiplier(
                            oldPoint.liveMultiplier()
                    );
            boolean evidenceReady =
                    row != null
                            && !"LEARNING"
                            .equalsIgnoreCase(
                                    row.state()
                            )
                            && row.confidence()
                            >= config
                            .minimumConfidence();
            double marketMacro =
                    evidenceReady
                            ? marketMacroMultiplier(
                            snapshot,
                            key,
                            macro,
                            activeRecentIssuers
                    )
                            : 1.0D;
            double target =
                    evidenceReady
                            ? validMultiplier(
                            row.recommendedMultiplier()
                    )
                            * marketMacro
                            : oldMultiplier;

            target =
                    Math.clamp(
                            target,
                            config.minimumMultiplier(),
                            config.maximumMultiplier()
                    );

            double next =
                    moveToward(
                            oldMultiplier,
                            target,
                            config.maximumStep()
                    );

            if (Math.abs(
                    next - 1.0D
            ) < 0.000_001D) {
                next = 1.0D;
            }

            double movement =
                    Math.abs(
                            next - oldMultiplier
                    );
            maximumObservedChange =
                    Math.max(
                            maximumObservedChange,
                            movement
                    );

            if (movement
                    >= config
                    .minimumPublishChange()) {
                changedMarkets++;
            }

            targets.put(
                    key,
                    target
            );
            requested.put(
                    key,
                    next
            );
        }

        boolean changed =
                changedMarkets > 0
                        && maximumObservedChange
                        >= config
                        .minimumPublishChange();

        return new PricingPlan(
                Map.copyOf(requested),
                Map.copyOf(targets),
                macro,
                recentIssuance,
                baselineIssuance,
                changedMarkets,
                changed
        );
    }

    private Map<String, PricePoint> pricePoints(
            ReferenceState reference,
            SellLearningService.EvaluationSnapshot snapshot,
            PricingPlan plan,
            LiveCompilation compilation
    ) {
        Map<String, PricePoint> points =
                new LinkedHashMap<>();

        for (String key
                : reference.dynamicMarketKeys()) {
            ReferenceMarket market =
                    reference
                            .markets()
                            .get(key);

            if (market == null) {
                continue;
            }

            LearningRow row =
                    snapshot
                            .markets()
                            .get(key);
            double requested =
                    plan.requestedMultipliers()
                            .getOrDefault(
                                    key,
                                    1.0D
                            );
            double effective =
                    compilation
                            .effectiveMultipliers()
                            .getOrDefault(
                                    key,
                                    requested
                            );
            double target =
                    plan.targetMultipliers()
                            .getOrDefault(
                                    key,
                                    requested
                            );

            PricePoint point =
                    new PricePoint(
                            key,
                            market.unitCents(),
                            requested,
                            effective,
                            target,
                            row == null
                                    ? 0.0D
                                    : Math.clamp(
                                    row.confidence(),
                                    0.0D,
                                    1.0D
                            ),
                            row == null
                                    ? "LEARNING"
                                    : row.state()
                    );
            points.put(
                    key,
                    point
            );
        }

        return Map.copyOf(points);
    }

    private double macroMultiplier(
            SellLearningService.EvaluationSnapshot snapshot,
            BigDecimal recentIssuance,
            BigDecimal baselineIssuance
    ) {
        if (!config.macroEnabled()
                || snapshot.recentPlayerHours()
                < config
                .macroMinimumRecentPlayerHours()
                || snapshot.baselinePlayerHours()
                < config
                .macroMinimumBaselinePlayerHours()
                || baselineIssuance.signum() <= 0
                || recentIssuance.signum() < 0) {
            return 1.0D;
        }

        double ratio;

        try {
            ratio =
                    recentIssuance
                            .divide(
                                    baselineIssuance,
                                    8,
                                    RoundingMode.HALF_UP
                            )
                            .doubleValue();
        } catch (ArithmeticException exception) {
            return 1.0D;
        }

        if (!Double.isFinite(ratio)
                || ratio
                <= config.macroSoftRatio()) {
            return 1.0D;
        }

        double span =
                Math.max(
                        0.01D,
                        config.macroFullRatio()
                                - config
                                .macroSoftRatio()
                );
        double pressure =
                Math.clamp(
                        (ratio
                                - config
                                .macroSoftRatio())
                                / span,
                        0.0D,
                        1.0D
                );

        return Math.clamp(
                1.0D
                        - (config
                        .macroMaximumBrake()
                        * pressure),
                1.0D
                        - config
                        .macroMaximumBrake(),
                1.0D
        );
    }

    private double marketMacroMultiplier(
            SellLearningService.EvaluationSnapshot snapshot,
            String marketKey,
            double globalMacroMultiplier,
            int activeRecentIssuers
    ) {
        if (snapshot == null
                || marketKey == null
                || activeRecentIssuers <= 0
                || globalMacroMultiplier >= 1.0D
                || snapshot.recentSellPayoutCents() <= 0L) {
            return 1.0D;
        }

        long marketPayout =
                snapshot
                        .recentMarketSellPayoutCents()
                        .getOrDefault(
                                marketKey,
                                0L
                        );

        if (marketPayout <= 0L) {
            return 1.0D;
        }

        double issuanceShare =
                Math.clamp(
                        (double) marketPayout
                                / (double) snapshot
                                .recentSellPayoutCents(),
                        0.0D,
                        1.0D
                );
        double relativePressure =
                Math.clamp(
                        issuanceShare
                                * activeRecentIssuers,
                        0.0D,
                        1.0D
                );
        double globalBrake =
                Math.clamp(
                        1.0D
                                - globalMacroMultiplier,
                        0.0D,
                        config.macroMaximumBrake()
                );

        return Math.clamp(
                1.0D
                        - (globalBrake
                        * relativePressure),
                1.0D
                        - config.macroMaximumBrake(),
                1.0D
        );
    }

    private BigDecimal issuancePerPlayerHour(
            long cents,
            double playerHours
    ) {
        if (cents <= 0L
                || !Double.isFinite(playerHours)
                || playerHours <= 0.0D) {
            return BigDecimal.ZERO;
        }

        return BigDecimal.valueOf(cents)
                .divide(
                        BigDecimal.valueOf(
                                playerHours
                        ),
                        8,
                        RoundingMode.HALF_UP
                );
    }

    private double moveToward(
            double current,
            double target,
            double maximumStep
    ) {
        double safeCurrent =
                validMultiplier(
                        current
                );
        double safeTarget =
                validMultiplier(
                        target
                );
        double delta =
                safeTarget
                        - safeCurrent;

        if (Math.abs(delta)
                <= maximumStep) {
            return safeTarget;
        }

        return safeCurrent
                + Math.copySign(
                maximumStep,
                delta
        );
    }

    private void initializeSqlAsync() {
        if (sqlConnecting
                || closed
                || !started
                || !config.enabled()
                || !storage.sqlConfigured()) {
            return;
        }

        sqlConnecting = true;
        long promotion =
                pendingPromotionId;

        try {
            executor.execute(
                    () -> {
                        Exception failure = null;

                        try {
                            storage.initialize();

                            if (promotion > 0L) {
                                storage.activateGeneration(
                                        promotion
                                );
                            }
                        } catch (Exception exception) {
                            failure =
                                    exception;
                        }

                        Exception finalFailure =
                                failure;

                        dispatchMain(
                                () -> finishSqlInitialization(
                                        promotion,
                                        finalFailure
                                )
                        );
                    }
            );
        } catch (RejectedExecutionException exception) {
            sqlConnecting = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + config.databaseRetryMillis();
        }
    }

    private synchronized void finishSqlInitialization(
            long promotion,
            Exception failure
    ) {
        sqlConnecting = false;

        if (closed) {
            return;
        }

        if (failure != null) {
            sqlReady = false;
            nextSqlRetryAt =
                    System.currentTimeMillis()
                            + config.databaseRetryMillis();

            core.getLogger().log(
                    Level.WARNING,
                    "Sell v10 live-price database recovery failed — current validated prices remain active",
                    failure
            );
            return;
        }

        if (promotion > 0L
                && pendingPromotionId
                == promotion) {
            pendingPromotionId = 0L;
        }

        sqlReady = true;
        nextSqlRetryAt = 0L;

        core.getLogger().info(
                "Sell v10 live-price database recovered — repricing is unfrozen"
        );
    }

    private boolean validGeneration(
            ReferenceState reference,
            Generation generation
    ) {
        if (reference == null
                || generation == null
                || generation.generationId()
                <= 0L
                || generation.catalogRevision()
                != SellPricingPolicy.CATALOG_REVISION
                || !reference.fingerprint()
                .equalsIgnoreCase(
                        generation
                                .referenceFingerprint()
                )
                || generation.prices().isEmpty()) {
            return false;
        }

        for (PricePoint point
                : generation.prices().values()) {
            if (point == null
                    || point.marketKey().isBlank()
                    || !reference
                    .dynamicMarketKeys()
                    .contains(
                            point.marketKey()
                    )
                    || !Double.isFinite(
                    point.liveMultiplier()
            )
                    || point.liveMultiplier()
                    < config.minimumMultiplier()
                    || point.liveMultiplier()
                    > config.maximumMultiplier()) {
                return false;
            }
        }

        return true;
    }

    private Map<String, ReferenceMarket>
    buildReferenceMarkets(
            SellCatalogSnapshot snapshot
    ) {
        Map<String, MarketAccumulator> accumulators =
                new LinkedHashMap<>();

        List<SellCatalogEntry> entries =
                new ArrayList<>(
                        snapshot.entries()
                                .values()
                );
        entries.sort(
                Comparator.comparing(
                        entry ->
                                entry.material()
                                        .name()
                )
        );

        for (SellCatalogEntry entry : entries) {
            if (entry == null
                    || entry.material() == null
                    || entry.baseCents() <= 0L
                    || entry.marketUnits() <= 0L
                    || !entry.serverSellEnabled()) {
                continue;
            }

            String key =
                    normalizeKey(
                            entry.marketKey()
                    );

            if (key.isBlank()) {
                continue;
            }

            BigDecimal unit =
                    BigDecimal.valueOf(
                                    entry.baseCents()
                            )
                            .divide(
                                    BigDecimal.valueOf(
                                            entry.marketUnits()
                                    ),
                                    8,
                                    RoundingMode.HALF_UP
                            );
            boolean eligible =
                    liveEligible(
                            entry
                    );
            MarketAccumulator current =
                    accumulators.get(key);

            if (current == null) {
                accumulators.put(
                        key,
                        new MarketAccumulator(
                                key,
                                entry.material(),
                                unit,
                                eligible
                        )
                );
                continue;
            }

            current.dynamicEligible =
                    current.dynamicEligible
                            || eligible;

            if (unit.compareTo(
                    current.unitCents
            ) < 0
                    || (unit.compareTo(
                    current.unitCents
            ) == 0
                    && entry.material()
                    .name()
                    .compareTo(
                            current.material
                                    .name()
                    ) < 0)) {
                current.material =
                        entry.material();
                current.unitCents =
                        unit;
            }
        }

        Map<String, ReferenceMarket> result =
                new LinkedHashMap<>();

        for (MarketAccumulator current
                : accumulators.values()) {
            result.put(
                    current.marketKey,
                    new ReferenceMarket(
                            current.marketKey,
                            current.material,
                            current.unitCents,
                            current.dynamicEligible
                    )
            );
        }

        return Map.copyOf(result);
    }

    private boolean liveEligible(
            SellCatalogEntry entry
    ) {
        if (entry == null
                || !entry.serverSellEnabled()
                || entry.priceSource() == null
                || entry.activationState() == null) {
            return false;
        }

        String source =
                entry.priceSource()
                        .trim()
                        .toUpperCase(
                                Locale.ROOT
                        );
        String category =
                normalizeCategory(
                        entry.category()
                );
        String activation =
                entry.activationState()
                        .trim()
                        .toUpperCase(
                                Locale.ROOT
                        );

        return (source.equals("CURATED")
                || source.equals(
                "GENERATED_COMMODITY"
        ))
                && config.dynamicCategories()
                .contains(category)
                && !activation.contains(
                "FLOOR"
        );
    }

    private Generation loadLocalGeneration() {
        if (!activeCacheFile.isFile()) {
            return null;
        }

        FileConfiguration cache =
                YamlConfiguration.loadConfiguration(
                        activeCacheFile
                );

        if (cache.getInt(
                "version",
                0
        ) != CACHE_VERSION) {
            return null;
        }

        long generationId =
                cache.getLong(
                        "generation-id",
                        0L
                );
        int catalogRevision =
                cache.getInt(
                        "catalog-revision",
                        0
                );
        String fingerprint =
                nonBlank(
                        cache.getString(
                                "reference-fingerprint",
                                ""
                        )
                );
        double macro =
                cache.getDouble(
                        "macro-multiplier",
                        1.0D
                );
        BigDecimal recent =
                decimal(
                        cache.getString(
                                "recent-issuance-cents-per-player-hour",
                                "0"
                        )
                );
        BigDecimal baseline =
                decimal(
                        cache.getString(
                                "baseline-issuance-cents-per-player-hour",
                                "0"
                        )
                );
        long evaluatedAt =
                cache.getLong(
                        "evaluated-at",
                        0L
                );
        long createdAt =
                cache.getLong(
                        "created-at",
                        0L
                );
        long activatedAt =
                cache.getLong(
                        "activated-at",
                        0L
                );

        Map<String, PricePoint> prices =
                new LinkedHashMap<>();

        ConfigurationSection marketsSection =
                cache.getConfigurationSection(
                        "markets"
                );

        if (marketsSection != null) {
            for (String rawKey
                    : marketsSection.getKeys(false)) {
                String key =
                        normalizeKey(rawKey);
                String path =
                        "markets."
                                + rawKey
                                + ".";

                if (key.isBlank()) {
                    continue;
                }

                PricePoint point =
                        new PricePoint(
                                key,
                                decimal(
                                        cache.getString(
                                                path
                                                        + "reference-unit-cents",
                                                "0"
                                        )
                                ),
                                cache.getDouble(
                                        path
                                                + "live-multiplier",
                                        1.0D
                                ),
                                cache.getDouble(
                                        path
                                                + "effective-multiplier",
                                        1.0D
                                ),
                                cache.getDouble(
                                        path
                                                + "target-multiplier",
                                        1.0D
                                ),
                                cache.getDouble(
                                        path
                                                + "confidence",
                                        0.0D
                                ),
                                cache.getString(
                                        path
                                                + "learning-state",
                                        "LEARNING"
                                )
                        );
                prices.put(
                        key,
                        point
                );
            }
        }

        return new Generation(
                generationId,
                catalogRevision,
                fingerprint,
                macro,
                recent,
                baseline,
                evaluatedAt,
                createdAt,
                activatedAt,
                prices
        );
    }

    private void writeGeneration(
            File file,
            Generation generation
    ) throws IOException {
        YamlConfiguration cache =
                new YamlConfiguration();

        cache.set(
                "version",
                CACHE_VERSION
        );
        cache.set(
                "generation-id",
                generation.generationId()
        );
        cache.set(
                "catalog-revision",
                generation.catalogRevision()
        );
        cache.set(
                "reference-fingerprint",
                generation.referenceFingerprint()
        );
        cache.set(
                "macro-multiplier",
                generation.macroMultiplier()
        );
        cache.set(
                "recent-issuance-cents-per-player-hour",
                generation
                        .recentIssuanceCentsPerPlayerHour()
                        .toPlainString()
        );
        cache.set(
                "baseline-issuance-cents-per-player-hour",
                generation
                        .baselineIssuanceCentsPerPlayerHour()
                        .toPlainString()
        );
        cache.set(
                "evaluated-at",
                generation.evaluatedAt()
        );
        cache.set(
                "created-at",
                generation.createdAt()
        );
        cache.set(
                "activated-at",
                generation.activatedAt()
        );

        List<String> keys =
                new ArrayList<>(
                        generation.prices()
                                .keySet()
                );
        keys.sort(String::compareTo);

        for (String key : keys) {
            PricePoint point =
                    generation.prices()
                            .get(key);
            String path =
                    "markets."
                            + key
                            + ".";

            cache.set(
                    path
                            + "reference-unit-cents",
                    point.referenceUnitCents()
                            .toPlainString()
            );
            cache.set(
                    path
                            + "live-multiplier",
                    point.liveMultiplier()
            );
            cache.set(
                    path
                            + "effective-multiplier",
                    point.effectiveMultiplier()
            );
            cache.set(
                    path
                            + "target-multiplier",
                    point.targetMultiplier()
            );
            cache.set(
                    path
                            + "confidence",
                    point.confidence()
            );
            cache.set(
                    path
                            + "learning-state",
                    point.learningState()
            );
        }

        File parent =
                file.getParentFile();

        if (parent != null
                && !parent.isDirectory()
                && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException(
                    "Could not create "
                            + parent
            );
        }

        cache.save(file);
    }

    private void queueActiveCacheWrite(
            Generation generation
    ) {
        if (generation == null
                || closed) {
            return;
        }

        try {
            executor.execute(
                    () -> {
                        File temporary =
                                new File(
                                        activeCacheFile
                                                .getParentFile(),
                                        activeCacheFile
                                                .getName()
                                                + ".tmp"
                                );

                        try {
                            writeGeneration(
                                    temporary,
                                    generation
                            );
                            replaceFile(
                                    temporary,
                                    activeCacheFile
                            );
                        } catch (Exception exception) {
                            core.getLogger().log(
                                    Level.WARNING,
                                    "Could not update Sell v10 active local price cache",
                                    exception
                            );
                        } finally {
                            try {
                                Files.deleteIfExists(
                                        temporary
                                                .toPath()
                                );
                            } catch (IOException ignored) {
                            }
                        }
                    }
            );
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void promoteStagedCache()
            throws IOException {
        if (!stagedCacheFile.isFile()) {
            throw new IOException(
                    "Missing staged Sell v10 local cache"
            );
        }

        replaceFile(
                stagedCacheFile,
                activeCacheFile
        );
    }

    private void replaceFile(
            File source,
            File target
    ) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption
                            .ATOMIC_MOVE,
                    StandardCopyOption
                            .REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption
                            .REPLACE_EXISTING
            );
        }
    }

    private void deleteStagedCache() {
        try {
            Files.deleteIfExists(
                    stagedCacheFile.toPath()
            );
        } catch (IOException ignored) {
        }
    }

    private void rejectStaged(
            long generationId
    ) {
        if (generationId <= 0L
                || closed) {
            return;
        }

        try {
            executor.execute(
                    () -> storage
                            .rejectGeneration(
                                    generationId
                            )
            );
        } catch (RejectedExecutionException ignored) {
        }
    }

    private String fingerprint(
            SellCatalogSnapshot snapshot
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );
            List<SellCatalogEntry> entries =
                    new ArrayList<>(
                            snapshot.entries()
                                    .values()
                    );
            entries.sort(
                    Comparator.comparing(
                            entry ->
                                    entry.material()
                                            .name()
                    )
            );

            updateDigest(
                    digest,
                    "revision="
                            + snapshot.revision()
            );
            updateDigest(
                    digest,
                    "rows="
                            + snapshot.expectedRows()
            );

            for (SellCatalogEntry entry : entries) {
                updateDigest(
                        digest,
                        entry.material().name()
                                + "|"
                                + entry.baseCents()
                                + "|"
                                + normalizeCategory(
                                entry.category()
                        )
                                + "|"
                                + normalizeKey(
                                entry.marketKey()
                        )
                                + "|"
                                + entry.marketUnits()
                                + "|"
                                + entry.priceSource()
                                + "|"
                                + entry.activationState()
                                + "|"
                                + entry.serverSellEnabled()
                                + "|"
                                + entry.buybackMultiplier()
                                + "|"
                                + entry.enchantBuybackMultiplier()
                );
            }

            byte[] bytes =
                    digest.digest();
            StringBuilder hex =
                    new StringBuilder(
                            bytes.length * 2
                    );

            for (byte value : bytes) {
                hex.append(
                        String.format(
                                Locale.ROOT,
                                "%02x",
                                value
                                        & 0xff
                        )
                );
            }

            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    exception
            );
        }
    }

    private void updateDigest(
            MessageDigest digest,
            String value
    ) {
        digest.update(
                value.getBytes(
                        StandardCharsets.UTF_8
                )
        );
        digest.update(
                (byte) '\n'
        );
    }

    private Generation withActivatedAt(
            Generation generation,
            long activatedAt
    ) {
        return new Generation(
                generation.generationId(),
                generation.catalogRevision(),
                generation.referenceFingerprint(),
                generation.macroMultiplier(),
                generation.recentIssuanceCentsPerPlayerHour(),
                generation.baselineIssuanceCentsPerPlayerHour(),
                generation.evaluatedAt(),
                generation.createdAt(),
                activatedAt,
                generation.prices()
        );
    }

    private BigDecimal decimal(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(
                    value.trim()
            );
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private double validMultiplier(
            double value
    ) {
        return Double.isFinite(value)
                && value > 0.0D
                ? value
                : 1.0D;
    }

    private String normalizeKey(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "";
        }

        return value.trim()
                .toUpperCase(
                        Locale.ROOT
                )
                .replace('-', '_')
                .replace(' ', '_');
    }

    private String normalizeCategory(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "misc";
        }

        return value.trim()
                .toLowerCase(
                        Locale.ROOT
                )
                .replace('-', '_')
                .replace(' ', '_');
    }

    private String nonBlank(
            String value
    ) {
        return value == null
                || value.isBlank()
                ? ""
                : value.trim();
    }

    private String percent(
            double fraction
    ) {
        return String.format(
                Locale.US,
                "%.1f%%",
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

    private String moneyRate(
            BigDecimal cents
    ) {
        if (cents == null
                || cents.signum() <= 0) {
            return "$0";
        }

        BigDecimal dollars =
                cents.movePointLeft(2)
                        .setScale(
                                2,
                                RoundingMode.HALF_UP
                        );

        return "$"
                + dollars
                .stripTrailingZeros()
                .toPlainString();
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

    public record ReferenceMarket(
            String marketKey,
            Material material,
            BigDecimal unitCents,
            boolean dynamicEligible
    ) {
        public ReferenceMarket {
            marketKey =
                    marketKey == null
                            ? ""
                            : marketKey;
            unitCents =
                    unitCents == null
                            ? BigDecimal.ZERO
                            : unitCents;
        }
    }

    private record ReferenceState(
            SellCatalogSnapshot snapshot,
            LiveAuthority authority,
            String fingerprint,
            Map<String, ReferenceMarket> markets,
            Set<String> dynamicMarketKeys
    ) {
        private ReferenceState {
            markets =
                    Map.copyOf(markets);
            dynamicMarketKeys =
                    Set.copyOf(
                            dynamicMarketKeys
                    );
        }
    }

    private record RestoreResult(
            ReferenceState reference,
            Generation selected,
            LiveCompilation compilation,
            String source,
            boolean databaseReady,
            Exception databaseFailure,
            Exception localFailure,
            long databaseGenerationId
    ) {
    }

    private record PricingPlan(
            Map<String, Double> requestedMultipliers,
            Map<String, Double> targetMultipliers,
            double macroMultiplier,
            BigDecimal recentIssuance,
            BigDecimal baselineIssuance,
            int changedMarkets,
            boolean changed
    ) {
        private PricingPlan {
            requestedMultipliers =
                    Map.copyOf(
                            requestedMultipliers
                    );
            targetMultipliers =
                    Map.copyOf(
                            targetMultipliers
                    );
        }
    }

    private static final class MarketAccumulator {
        private final String marketKey;
        private Material material;
        private BigDecimal unitCents;
        private boolean dynamicEligible;

        private MarketAccumulator(
                String marketKey,
                Material material,
                BigDecimal unitCents,
                boolean dynamicEligible
        ) {
            this.marketKey =
                    marketKey;
            this.material =
                    material;
            this.unitCents =
                    unitCents;
            this.dynamicEligible =
                    dynamicEligible;
        }
    }

    private record LiveConfig(
            boolean enabled,
            Set<String> dynamicCategories,
            double maximumStep,
            double minimumMultiplier,
            double maximumMultiplier,
            double minimumConfidence,
            double minimumPublishChange,
            boolean macroEnabled,
            double macroMinimumRecentPlayerHours,
            double macroMinimumBaselinePlayerHours,
            double macroSoftRatio,
            double macroFullRatio,
            double macroMaximumBrake,
            long databaseRetryMillis,
            long generationRetentionMillis
    ) {
        private static final Set<String>
                DEFAULT_DYNAMIC_CATEGORIES =
                Set.of(
                        "blocks",
                        "ores",
                        "wood",
                        "farming",
                        "mob_drops",
                        "nether",
                        "end"
                );

        private static LiveConfig from(
                FileConfiguration config
        ) {
            List<String> configuredCategories =
                    config.getStringList(
                            "learning.live.dynamic-categories"
                    );
            Set<String> categories =
                    new HashSet<>();

            if (configuredCategories.isEmpty()) {
                categories.addAll(
                        DEFAULT_DYNAMIC_CATEGORIES
                );
            } else {
                for (String raw
                        : configuredCategories) {
                    if (raw == null
                            || raw.isBlank()) {
                        continue;
                    }

                    categories.add(
                            raw.trim()
                                    .toLowerCase(
                                            Locale.ROOT
                                    )
                                    .replace('-', '_')
                                    .replace(' ', '_')
                    );
                }
            }

            double minimum =
                    Math.clamp(
                            config.getDouble(
                                    "learning.live.minimum-multiplier",
                                    0.70D
                            ),
                            0.25D,
                            1.0D
                    );
            double maximum =
                    Math.clamp(
                            config.getDouble(
                                    "learning.live.maximum-multiplier",
                                    1.25D
                            ),
                            1.0D,
                            2.0D
                    );
            double soft =
                    Math.clamp(
                            config.getDouble(
                                    "learning.live.macro.soft-ratio",
                                    1.25D
                            ),
                            1.01D,
                            5.0D
                    );
            double full =
                    Math.clamp(
                            config.getDouble(
                                    "learning.live.macro.full-ratio",
                                    2.00D
                            ),
                            soft + 0.01D,
                            10.0D
                    );

            return new LiveConfig(
                    config.getBoolean(
                            "learning.live.enabled",
                            true
                    ),
                    Set.copyOf(categories),
                    Math.clamp(
                            config.getDouble(
                                    "learning.live.maximum-step-percent",
                                    4.0D
                            ) / 100.0D,
                            0.0025D,
                            0.10D
                    ),
                    minimum,
                    Math.max(
                            minimum,
                            maximum
                    ),
                    Math.clamp(
                            config.getDouble(
                                    "learning.live.minimum-confidence",
                                    0.50D
                            ),
                            0.10D,
                            1.0D
                    ),
                    Math.clamp(
                            config.getDouble(
                                    "learning.live.minimum-publish-change-percent",
                                    0.25D
                            ) / 100.0D,
                            0.0005D,
                            0.05D
                    ),
                    config.getBoolean(
                            "learning.live.macro.enabled",
                            true
                    ),
                    Math.max(
                            1.0D,
                            config.getDouble(
                                    "learning.live.macro.minimum-recent-player-hours",
                                    8.0D
                            )
                    ),
                    Math.max(
                            1.0D,
                            config.getDouble(
                                    "learning.live.macro.minimum-baseline-player-hours",
                                    24.0D
                            )
                    ),
                    soft,
                    full,
                    Math.clamp(
                            config.getDouble(
                                    "learning.live.macro.maximum-brake-percent",
                                    15.0D
                            ) / 100.0D,
                            0.0D,
                            0.40D
                    ),
                    Math.clamp(
                            config.getLong(
                                    "learning.live.database-retry-minutes",
                                    5L
                            ),
                            1L,
                            60L
                    ) * MINUTE_MILLIS,
                    Math.clamp(
                            config.getLong(
                                    "learning.live.generation-retention-days",
                                    30L
                            ),
                            7L,
                            180L
                    ) * DAY_MILLIS
            );
        }
    }
}
