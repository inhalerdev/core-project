package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import net.mineacle.core.sell.model.SellCatalogEntry;
import net.mineacle.core.sell.model.SellCatalogSnapshot;
import net.mineacle.core.sell.service.SellPricingPolicy;
import net.mineacle.core.sell.service.SellService;
import net.mineacle.core.sell.service.SellVariantValuationService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Sell/Worth catalog revision 9.
 *
 * <p>The generated catalog is the immediate runtime pricing authority while
 * SQL remains durable/operator-editable persistence. Normal survival-obtainable
 * materials receive a positive liquidation floor. Reversible commodity groups
 * conserve value exactly, one-way recipes preserve the configured crafting
 * haircut, and cent-floor requirements propagate backward through recipe inputs
 * before final anti-arbitrage caps are applied.</p>
 *
 * <p>Recipes that depend on exact/custom metadata are intentionally isolated
 * from the automatic Material graph. They must never disable or distort the
 * normal vanilla-material catalog.</p>
 */
@SuppressWarnings("SqlNoDataSourceInspection")
public final class SellCatalogBootstrapService {

    private static final String DEFAULT_PREFIX = "mineacle_sell";
    private static final int DERIVATION_PASSES = 256;

    private final Core core;
    private final SellService sellService;
    private final AtomicBoolean started = new AtomicBoolean();

    /*
     * These snapshots are published only after the post-enable recipe registry
     * has been captured on the server thread. SQL bootstrap starts afterwards,
     * so asynchronous persistence never observes a partially built catalog.
     */
    private volatile List<CatalogSeed> seeds = List.of();
    private volatile CatalogSummary summary =
            new CatalogSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public SellCatalogBootstrapService(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        /*
         * MineacleCore can be enabled before plugins that register recipes.
         * Building here would make the catalog and the delayed runtime audit
         * inspect different registries. One server-thread tick defers capture
         * until the plugin enable sequence has completed.
         */
        core.getServer().getScheduler().runTask(
                core,
                this::buildAndActivate
        );
    }

    private void buildAndActivate() {
        if (!core.isEnabled()) {
            return;
        }

        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(
                    new File(core.getDataFolder(), "sell.yml")
            );
            Set<Material> blocked = configuredBlockedMaterials(config);
            List<RecipeSeed> recipes = snapshotRecipes();
            CatalogBuild build = buildCatalog(config, blocked, recipes);

            this.seeds = build.seeds();
            this.summary = build.summary();

            SellCatalogSnapshot builtIn = seedSnapshot();
            if (!sellService.activateCatalogSnapshot(builtIn)) {
                core.getLogger().severe(
                        "Sell catalog v9 built-in activation failed — previous pricing authority remains active"
                );
                return;
            }

            core.getLogger().info(
                    "Sell catalog v9 built-in authority activated — "
                            + summary.total() + " materials, "
                            + summary.sellEnabled() + " server-sellable, "
                            + summary.oneCent() + " at $0.01, "
                            + summary.unsafe() + " mechanically unavailable, "
                            + recipes.size() + " post-enable recipes audited"
            );

            core.getServer().getScheduler().runTaskAsynchronously(
                    core,
                    this::bootstrapSql
            );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Sell catalog v9 post-enable build failed — previous pricing authority remains active",
                    exception
            );
        }
    }

    private void bootstrapSql() {
        try {
            FileConfiguration sellConfig = YamlConfiguration.loadConfiguration(
                    new File(core.getDataFolder(), "sell.yml")
            );
            String storage = sellConfig.getString("market.storage", "mysql");

            if (!storage.equalsIgnoreCase("mysql")
                    && !storage.equalsIgnoreCase("mariadb")) {
                core.getLogger().warning(
                        "Sell catalog v9 SQL persistence skipped — built-in v9 pricing remains authoritative"
                );
                return;
            }

            File databaseFile = new File(
                    core.getDataFolder(),
                    nonBlank(
                            sellConfig.getString(
                                    "market.database-config-file",
                                    "webprofiles.yml"
                            ),
                            "webprofiles.yml"
                    )
            );
            FileConfiguration databaseConfig =
                    YamlConfiguration.loadConfiguration(databaseFile);
            String driverClass = value(
                    databaseConfig,
                    "database.driver-class",
                    "com.mysql.cj.jdbc.Driver"
            );
            String jdbcUrl = value(
                    databaseConfig,
                    "database.jdbc-url",
                    "jdbc:mysql://127.0.0.1:3306/mineacle"
            );
            String username = value(
                    databaseConfig,
                    "database.username",
                    "mineacle_core"
            );
            String password = value(
                    databaseConfig,
                    "database.password",
                    ""
            );
            String prefix = safeIdentifier(
                    sellConfig.getString("market.table-prefix", DEFAULT_PREFIX)
            );
            String table = prefix + "_items";
            String metaTable = prefix + "_catalog_meta";

            Class.forName(driverClass);

            SellCatalogSnapshot readySnapshot;
            DatabaseAudit audit;

            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl,
                    username,
                    password
            )) {
                initialize(connection, table, metaTable);
                upsertSeeds(connection, table);
                audit = audit(connection, table);
                writeMeta(connection, metaTable, audit);

                if (!audit.ready()) {
                    throw new IllegalStateException(
                            "Sell catalog v9 SQL audit failed: missing="
                                    + audit.missingRows()
                                    + ", invalid="
                                    + audit.invalidRows()
                    );
                }

                readySnapshot = loadSnapshot(connection, table);
            }

            SellCatalogSnapshot snapshot = readySnapshot;
            core.getServer().getScheduler().runTask(
                    core,
                    () -> {
                        if (!core.isEnabled()) {
                            return;
                        }
                        if (!sellService.activateCatalogSnapshot(snapshot)) {
                            core.getLogger().warning(
                                    "Sell catalog v9 SQL snapshot was READY but runtime activation was rejected — built-in v9 authority remains active"
                            );
                        }
                    }
            );

            core.getLogger().info(
                    "Sell catalog v9 READY — "
                            + audit.validRows() + "/" + audit.expectedRows()
                            + " rows valid, "
                            + audit.sellEnabledRows() + " sell-enabled, "
                            + audit.operatorLockedRows() + " operator-locked — "
                            + summary.curated() + " curated anchors, "
                            + summary.commodity() + " commodity-normalized, "
                            + summary.recipe() + " recipe-derived, "
                            + summary.fallback() + " fallback-derived, "
                            + summary.variant() + " variant families, "
                            + summary.oneCent() + " one-cent values, "
                            + summary.commodityGroups() + " commodity groups"
            );
        } catch (Exception exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Sell catalog v9 SQL persistence unavailable — built-in v9 pricing remains authoritative",
                    exception
            );
        }
    }

    private CatalogBuild buildCatalog(
            FileConfiguration config,
            Set<Material> blocked,
            List<RecipeSeed> recipes
    ) {
        List<Material> eligible = new ArrayList<>();

        for (Material material : Material.values()) {
            if (eligibleMaterial(material, blocked)) {
                eligible.add(material);
            }
        }
        eligible.sort(Comparator.comparing(Material::name));

        Map<Material, Draft> drafts = new EnumMap<>(Material.class);

        for (Material material : eligible) {
            String category = normalizeCategory(sellService.category(material));
            long configuredBase = sellService.baseWorthCents(material);
            boolean curated = configuredBase > 0L
                    && sellService.isExplicitlyPriced(material);
            long fallback = configuredMoneyCents(
                    config,
                    "fallback-prices." + category,
                    SellPricingPolicy.categoryPolicy(category).defaultSeedCents()
            );
            long base = curated
                    ? SellPricingPolicy.curatedLiquidationCents(
                    category,
                    configuredBase
            )
                    : SellPricingPolicy.automaticSeedCents(
                    category,
                    fallback
            );
            String itemPath = "prices." + material.name();
            SellPricingPolicy.MarketBounds bounds =
                    SellPricingPolicy.marketBounds(
                            category,
                            config.getDouble(
                                    itemPath + ".minimum-multiplier",
                                    config.getDouble(
                                            "market.minimum-multiplier",
                                            SellPricingPolicy.categoryPolicy(category)
                                                    .defaultMinimumMultiplier()
                                    )
                            ),
                            config.getDouble(
                                    itemPath + ".maximum-multiplier",
                                    config.getDouble(
                                            "market.maximum-multiplier",
                                            SellPricingPolicy.categoryPolicy(category)
                                                    .maximumMultiplier()
                                    )
                            )
                    );
            boolean marketEnabled =
                    SellPricingPolicy.automaticMarketEnabled(category)
                            && config.getBoolean(
                            "market.categories." + category + ".enabled",
                            true
                    );
            long target = Math.max(
                    1L,
                    config.getLong(
                            itemPath + ".target-units-per-day",
                            config.getLong(
                                    "market.targets." + category,
                                    1_000L
                            )
                    )
            );
            double categoryEnchantBuyback = Math.clamp(
                    config.getDouble(
                            "valuation.category-buyback."
                                    + category + ".enchants",
                            0.50D
                    ),
                    0.0D,
                    1.0D
            );
            double enchantBuyback = Math.clamp(
                    config.getDouble(
                            itemPath + ".enchant-buyback-multiplier",
                            categoryEnchantBuyback
                    ),
                    0.0D,
                    1.0D
            );

            drafts.put(
                    material,
                    new Draft(
                            material,
                            category,
                            Math.max(1L, base),
                            marketEnabled,
                            bounds.minimumMultiplier(),
                            bounds.maximumMultiplier(),
                            material.name(),
                            1L,
                            target,
                            curated
                                    ? PriceSource.CURATED
                                    : PriceSource.GENERATED_CATEGORY,
                            curated,
                            enchantBuyback
                    )
            );
        }

        CommodityBuild commodities = discoverCommodities(
                eligible,
                recipes,
                drafts
        );
        applyCommodityAuthority(drafts, commodities);
        normalizeDynamicCentFloors(drafts, commodities);

        /*
         * A positive cent floor plus an unconditional 82% haircut is
         * mathematically impossible around a crafting cycle. Detect those
         * cycles once. They may use the strict no-profit boundary only when
         * establishing the minimum viable floor; final pricing still attempts
         * the normal 82% ceiling first.
         */
        Set<RecipeSeed> cyclicRecipes = cyclicRecipes(recipes);

        Map<Material, List<RecipeSeed>> byOutput = new EnumMap<>(Material.class);
        for (int index = 0; index < recipes.size(); index++) {
            if (commodities.equivalentRecipeIndexes().contains(index)) {
                continue;
            }

            RecipeSeed recipe = recipes.get(index);
            if (untrustedCatalogRecipe(recipe)
                    || SellVariantValuationService.supportsMaterial(recipe.output())) {
                continue;
            }

            byOutput.computeIfAbsent(
                    recipe.output(),
                    ignored -> new ArrayList<>()
            ).add(recipe);
        }

        /*
         * Crafted outputs are intentionally static. Demand motion belongs on
         * primary commodities/resources; letting both sides of a recipe move
         * independently would create a moving arbitrage boundary.
         */
        for (Material output : byOutput.keySet()) {
            if (commodities.info().containsKey(output)) {
                continue;
            }
            Draft draft = drafts.get(output);
            if (draft != null) {
                draft.marketEnabled = false;
                draft.minimumMultiplier = 1.0D;
                draft.maximumMultiplier = 1.0D;
            }
        }

        Map<Material, Long> minimumPayoutRequirements =
                propagateMinimumCentFloors(
                        drafts,
                        byOutput,
                        commodities,
                        cyclicRecipes
                );

        /*
         * Final anti-arbitrage caps are monotonic-decreasing. The floor pass
         * above has already increased upstream opportunity cost enough to make
         * ordinary outputs positively liquid. These passes only clamp values
         * that remain above their cheapest reachable input boundary.
         */
        for (int pass = 0; pass < DERIVATION_PASSES; pass++) {
            boolean changed = false;

            for (Map.Entry<Material, List<RecipeSeed>> entry
                    : byOutput.entrySet()) {
                Material output = entry.getKey();
                Draft draft = drafts.get(output);

                if (draft == null) {
                    continue;
                }

                CommodityInfo commodityInfo = commodities.info().get(output);
                boolean commodityOutput = commodityInfo != null;
                double outputMaximum =
                        commodityOutput && draft.marketEnabled
                                ? draft.maximumMultiplier
                                : 1.0D;
                long safeCeiling = Long.MAX_VALUE;
                boolean auditedRecipe = false;

                for (RecipeSeed recipe : entry.getValue()) {
                    long inputBudget = ingredientMinimumBudget(recipe, drafts);

                    /*
                     * A custom/metadata recipe never reaches this collection.
                     * A negative budget here means a runtime-unsafe remainder or
                     * overflow path; ignore that recipe for automatic Material
                     * pricing instead of disabling an otherwise normal item.
                     */
                    if (inputBudget < 0L) {
                        continue;
                    }

                    auditedRecipe = true;
                    long retainedCeiling =
                            SellPricingPolicy.outputBaseCeilingCents(
                                    inputBudget,
                                    recipe.outputAmount(),
                                    outputMaximum
                            );

                    long requiredPayout = minimumPayoutRequirements.getOrDefault(
                            output,
                            1L
                    );
                    long requiredBase = requiredBaseForOutput(
                            draft,
                            commodityInfo,
                            requiredPayout,
                            minimumPayoutRequirements,
                            commodities
                    );

                    if (retainedCeiling < requiredBase) {
                        /*
                         * Integer-cent edge case: permit equality with the
                         * direct input liquidation boundary, but never profit.
                         */
                        long hardCeiling = hardNoProfitUnitCeiling(
                                inputBudget,
                                recipe.outputAmount()
                        );
                        if (hardCeiling >= requiredBase) {
                            retainedCeiling = hardCeiling;
                        }
                    }

                    safeCeiling = Math.min(safeCeiling, retainedCeiling);
                }

                if (!auditedRecipe) {
                    continue;
                }

                long requiredPayout = minimumPayoutRequirements.getOrDefault(
                        output,
                        1L
                );
                long requiredBase = requiredBaseForOutput(
                        draft,
                        commodityInfo,
                        requiredPayout,
                        minimumPayoutRequirements,
                        commodities
                );

                if (safeCeiling <= 0L || safeCeiling < requiredBase) {
                    if (commodityOutput) {
                        markCommodityUnsafe(
                                drafts,
                                commodities,
                                commodityInfo.marketKey()
                        );
                    } else {
                        draft.safe = false;
                    }
                    continue;
                }

                if (commodityOutput) {
                    long unitCeiling = safeCeiling
                            / Math.max(1L, commodityInfo.marketUnits());

                    if (unitCeiling <= 0L) {
                        markCommodityUnsafe(
                                drafts,
                                commodities,
                                commodityInfo.marketKey()
                        );
                        continue;
                    }

                    if (lowerCommodityUnitPrice(
                            drafts,
                            commodities,
                            commodityInfo.marketKey(),
                            unitCeiling
                    )) {
                        changed = true;
                    }
                } else {
                    long next = draft.curated
                            ? Math.clamp(
                            draft.baseCents,
                            requiredBase,
                            safeCeiling
                    )
                            : safeCeiling;

                    if (next != draft.baseCents) {
                        draft.baseCents = next;
                        draft.recipeCapped = true;
                        changed = true;
                    }
                }

                if (draft.source != PriceSource.CURATED
                        && draft.source != PriceSource.GENERATED_COMMODITY) {
                    draft.source = PriceSource.GENERATED_RECIPE;
                } else if (draft.source == PriceSource.CURATED) {
                    draft.recipeCapped = true;
                }
            }

            if (!changed) {
                break;
            }
        }

        /* Variant families are metadata-priced at runtime and never float. */
        for (Draft draft : drafts.values()) {
            if (!SellVariantValuationService.supportsMaterial(draft.material)) {
                continue;
            }
            draft.baseCents = Math.max(
                    1L,
                    SellVariantValuationService.catalogBaseCents(draft.material)
            );
            draft.marketEnabled = false;
            draft.minimumMultiplier = 1.0D;
            draft.maximumMultiplier = 1.0D;
            draft.enchantBuybackMultiplier = 0.0D;
            draft.source = PriceSource.VARIANT_REQUIRED;
            draft.safe = true;
        }

        List<CatalogSeed> result = new ArrayList<>(drafts.size());
        int curated = 0;
        int commodity = 0;
        int recipe = 0;
        int fallback = 0;
        int variant = 0;
        int oneCent = 0;
        int unsafe = 0;
        int sellEnabled = 0;

        for (Material material : eligible) {
            Draft draft = drafts.get(material);
            boolean variantMaterial =
                    SellVariantValuationService.supportsMaterial(material);
            boolean serverSellEnabled = draft.safe && draft.baseCents > 0L;

            if (serverSellEnabled) {
                sellEnabled++;
            } else {
                unsafe++;
            }
            if (draft.baseCents == 1L) {
                oneCent++;
            }

            switch (draft.source) {
                case CURATED -> curated++;
                case GENERATED_COMMODITY -> commodity++;
                case GENERATED_RECIPE -> recipe++;
                case GENERATED_CATEGORY -> fallback++;
                case VARIANT_REQUIRED -> variant++;
            }

            String activation = activationState(
                    draft,
                    variantMaterial,
                    serverSellEnabled
            );

            result.add(
                    new CatalogSeed(
                            material.name(),
                            draft.category,
                            Math.max(1L, draft.baseCents),
                            serverSellEnabled,
                            serverSellEnabled && draft.marketEnabled,
                            draft.marketKey,
                            Math.max(1L, draft.marketUnits),
                            draft.targetUnitsPerDay,
                            draft.marketEnabled
                                    ? draft.minimumMultiplier
                                    : 1.0D,
                            draft.marketEnabled
                                    ? draft.maximumMultiplier
                                    : 1.0D,
                            1.0D,
                            draft.enchantBuybackMultiplier,
                            draft.source.name(),
                            serverSellEnabled,
                            activation,
                            false,
                            SellPricingPolicy.CATALOG_REVISION
                    )
            );
        }

        return new CatalogBuild(
                List.copyOf(result),
                new CatalogSummary(
                        result.size(),
                        sellEnabled,
                        curated,
                        commodity,
                        recipe,
                        fallback,
                        variant,
                        oneCent,
                        unsafe,
                        commodities.groupCount()
                )
        );
    }

    /**
     * Positive-name predicate used directly by every caller. Metadata-exact
     * recipes are outside the automatic Material graph and are therefore
     * ignored rather than inverted into a catalog-disable condition.
     */
    private boolean untrustedCatalogRecipe(RecipeSeed recipe) {
        if (recipe == null
                || recipe.output() == null
                || recipe.output() == Material.AIR
                || recipe.outputAmount() <= 0
                || recipe.ingredients().isEmpty()) {
            return true;
        }

        for (IngredientChoice choice : recipe.ingredients()) {
            if (choice == null
                    || choice.untrusted()
                    || choice.materials().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private Set<RecipeSeed> cyclicRecipes(
            List<RecipeSeed> recipes
    ) {
        Map<Material, Set<Material>> graph =
                new EnumMap<>(Material.class);
        List<RecipeSeed> candidates =
                new ArrayList<>();

        for (RecipeSeed recipe : recipes) {
            if (untrustedCatalogRecipe(recipe)
                    || SellVariantValuationService.supportsMaterial(
                    recipe.output()
            )) {
                continue;
            }

            candidates.add(recipe);

            for (IngredientChoice choice : recipe.ingredients()) {
                for (Material ingredient : choice.materials()) {
                    graph.computeIfAbsent(
                            ingredient,
                            ignored -> EnumSet.noneOf(Material.class)
                    ).add(recipe.output());
                }
            }
        }

        Set<RecipeSeed> cyclic =
                new HashSet<>();

        for (RecipeSeed recipe : candidates) {
            for (IngredientChoice choice : recipe.ingredients()) {
                boolean found = false;

                for (Material ingredient : choice.materials()) {
                    if (ingredient == recipe.output()
                            || reachableMaterial(
                            recipe.output(),
                            ingredient,
                            graph
                    )) {
                        cyclic.add(recipe);
                        found = true;
                        break;
                    }
                }

                if (found) {
                    break;
                }
            }
        }

        return Set.copyOf(cyclic);
    }

    private boolean reachableMaterial(
            Material start,
            Material target,
            Map<Material, Set<Material>> graph
    ) {
        if (start == null || target == null) {
            return false;
        }
        if (start == target) {
            return true;
        }

        Set<Material> visited =
                EnumSet.noneOf(Material.class);
        Queue<Material> queue =
                new ArrayDeque<>();
        visited.add(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            Material current = queue.remove();

            for (Material next : graph.getOrDefault(
                    current,
                    Set.of()
            )) {
                if (next == target) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        return false;
    }

    /**
     * Solves the one-cent liquidity requirement backward through trusted
     * recipes. Every ordinary catalog material starts with a one-cent minimum.
     * If an output needs more input opportunity cost to preserve the crafting
     * haircut, that minimum is propagated into every usable alternative for the
     * relevant ingredient slots. The pass is bounded and monotonic-increasing.
     */
    private Map<Material, Long> propagateMinimumCentFloors(
            Map<Material, Draft> drafts,
            Map<Material, List<RecipeSeed>> byOutput,
            CommodityBuild commodities,
            Set<RecipeSeed> cyclicRecipes
    ) {
        Map<Material, Long> required = new EnumMap<>(Material.class);
        Set<Material> returnedContainers =
                craftingRemainderMaterials(
                        drafts.keySet()
                );

        for (Draft draft : drafts.values()) {
            if (draft.safe && draft.baseCents > 0L) {
                required.put(draft.material, 1L);
            }
        }

        for (int pass = 0; pass < DERIVATION_PASSES; pass++) {
            boolean changed =
                    normalizeCommodityRequirements(
                            required,
                            commodities
                    );

            for (Map.Entry<Material, List<RecipeSeed>> entry
                    : byOutput.entrySet()) {
                long outputMinimum = required.getOrDefault(entry.getKey(), 1L);

                for (RecipeSeed recipe : entry.getValue()) {
                    long requiredBudget = requiredIngredientBudget(
                            outputMinimum,
                            recipe.outputAmount(),
                            cyclicRecipes.contains(recipe)
                    );

                    if (requiredBudget <= 0L
                            || requiredBudget == Long.MAX_VALUE) {
                        continue;
                    }

                    long currentBudget =
                            requiredRecipeInputBudget(
                                    recipe,
                                    required
                            );

                    if (currentBudget < 0L
                            || currentBudget >= requiredBudget) {
                        continue;
                    }

                    long deficit =
                            requiredBudget - currentBudget;

                    if (raiseRecipeInputDeficit(
                            recipe,
                            deficit,
                            required,
                            returnedContainers
                    )) {
                        changed = true;
                    }
                }
            }

            if (!changed) {
                break;
            }
        }

        applyMinimumRequirements(drafts, commodities, required);
        return Map.copyOf(required);
    }

    private boolean normalizeCommodityRequirements(
            Map<Material, Long> requirements,
            CommodityBuild commodities
    ) {
        Map<String, Long> unitFloors =
                new HashMap<>();

        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            CommodityInfo info =
                    entry.getValue();
            long requiredPayout =
                    Math.max(
                            1L,
                            requirements.getOrDefault(
                                    entry.getKey(),
                                    1L
                            )
                    );
            long unitFloor =
                    divideCeiling(
                            requiredPayout,
                            Math.max(
                                    1L,
                                    info.marketUnits()
                            )
                    );

            unitFloors.merge(
                    info.marketKey(),
                    Math.max(
                            1L,
                            unitFloor
                    ),
                    Math::max
            );
        }

        boolean changed = false;

        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            CommodityInfo info =
                    entry.getValue();
            long unitFloor =
                    unitFloors.getOrDefault(
                            info.marketKey(),
                            1L
                    );
            long requiredPayout =
                    safeMultiply(
                            unitFloor,
                            Math.max(
                                    1L,
                                    info.marketUnits()
                            )
                    );

            if (raiseRequirement(
                    requirements,
                    entry.getKey(),
                    requiredPayout
            )) {
                changed = true;
            }
        }

        return changed;
    }

    private long requiredIngredientBudget(
            long outputMinimumCents,
            int outputAmount,
            boolean cyclic
    ) {
        long syntheticOneCentOutputs = safeMultiply(
                Math.max(1L, outputMinimumCents),
                Math.max(1L, outputAmount)
        );

        if (syntheticOneCentOutputs <= 0L
                || syntheticOneCentOutputs == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }

        try {
            BigDecimal retention = cyclic
                    ? BigDecimal.ONE
                    : BigDecimal.valueOf(
                    SellPricingPolicy.ONE_WAY_RECIPE_RETENTION
            );
            return BigDecimal.valueOf(syntheticOneCentOutputs)
                    .divide(
                            retention,
                            0,
                            RoundingMode.CEILING
                    )
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long requiredRecipeInputBudget(
            RecipeSeed recipe,
            Map<Material, Long> requirements
    ) {
        long total = 0L;

        for (IngredientChoice choice : recipe.ingredients()) {
            long minimum = requiredChoiceNetPayout(
                    choice,
                    recipe.craftingRemainders(),
                    requirements
            );

            if (minimum < 0L) {
                return -1L;
            }

            total = safeAdd(total, minimum);
            if (total == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
        }

        return total;
    }

    private long requiredChoiceNetPayout(
            IngredientChoice choice,
            boolean craftingRemainders,
            Map<Material, Long> requirements
    ) {
        long cheapest = Long.MAX_VALUE;

        for (Material material : choice.materials()) {
            long gross = requirements.getOrDefault(
                    material,
                    0L
            );

            if (gross <= 0L) {
                continue;
            }

            long returned = craftingRemainders
                    ? requiredRemainderPayout(
                    material,
                    requirements
            )
                    : 0L;
            long net = Math.max(
                    0L,
                    gross - returned
            );
            cheapest = Math.min(
                    cheapest,
                    net
            );
        }

        return cheapest == Long.MAX_VALUE
                ? -1L
                : cheapest;
    }

    private long requiredRemainderPayout(
            Material material,
            Map<Material, Long> requirements
    ) {
        Material remainder =
                material.getCraftingRemainingItem();

        if (remainder == null
                || remainder == Material.AIR) {
            return 0L;
        }

        return Math.max(
                0L,
                requirements.getOrDefault(
                        remainder,
                        0L
                )
        );
    }

    private boolean raiseRecipeInputDeficit(
            RecipeSeed recipe,
            long deficit,
            Map<Material, Long> requirements,
            Set<Material> returnedContainers
    ) {
        if (deficit <= 0L) {
            return false;
        }

        IngredientChoice selected = null;
        long selectedNet = 0L;
        int selectedScore = Integer.MAX_VALUE;

        for (IngredientChoice choice : recipe.ingredients()) {
            long net = requiredChoiceNetPayout(
                    choice,
                    recipe.craftingRemainders(),
                    requirements
            );

            if (net < 0L) {
                continue;
            }

            int score = choice.materials().size() * 4;

            if (recipe.craftingRemainders()
                    && choiceHasCraftingRemainder(choice)) {
                score += 2;
            }

            if (choiceContainsAny(
                    choice,
                    returnedContainers
            )) {
                /*
                 * Prefer raising the consumed commodity rather than a reusable
                 * container such as GLASS_BOTTLE/BUCKET. Raising the returned
                 * container can create a false positive feedback loop in an
                 * otherwise perfectly conservative reversible recipe family.
                 */
                score += 16;
            }

            if (choice.materials().contains(
                    recipe.output()
            )) {
                score += 1;
            }

            if (selected == null
                    || score < selectedScore
                    || (score == selectedScore
                    && net < selectedNet)) {
                selected = choice;
                selectedNet = net;
                selectedScore = score;
            }
        }

        if (selected == null) {
            return false;
        }

        int matchingSlots = 0;

        for (IngredientChoice choice : recipe.ingredients()) {
            if (choice.equals(selected)) {
                matchingSlots++;
            }
        }

        long perSlotDeficit =
                divideCeiling(
                        deficit,
                        matchingSlots
                );
        long targetNet =
                safeAdd(
                        selectedNet,
                        perSlotDeficit
                );

        if (targetNet <= 0L
                || targetNet == Long.MAX_VALUE) {
            return false;
        }

        boolean changed = false;

        for (Material material : selected.materials()) {
            long returned = recipe.craftingRemainders()
                    ? requiredRemainderPayout(
                    material,
                    requirements
            )
                    : 0L;
            long grossRequired =
                    safeAdd(
                            targetNet,
                            returned
                    );

            if (raiseRequirement(
                    requirements,
                    material,
                    grossRequired
            )) {
                changed = true;
            }
        }

        return changed;
    }

    private Set<Material> craftingRemainderMaterials(
            Set<Material> materials
    ) {
        Set<Material> result =
                EnumSet.noneOf(Material.class);

        for (Material material : materials) {
            Material remainder =
                    material.getCraftingRemainingItem();

            if (remainder != null
                    && remainder != Material.AIR) {
                result.add(remainder);
            }
        }

        return Set.copyOf(result);
    }

    private boolean choiceContainsAny(
            IngredientChoice choice,
            Set<Material> materials
    ) {
        for (Material material : choice.materials()) {
            if (materials.contains(material)) {
                return true;
            }
        }

        return false;
    }

    private boolean choiceHasCraftingRemainder(
            IngredientChoice choice
    ) {
        for (Material material : choice.materials()) {
            Material remainder =
                    material.getCraftingRemainingItem();

            if (remainder != null
                    && remainder != Material.AIR) {
                return true;
            }
        }

        return false;
    }

    private boolean raiseRequirement(
            Map<Material, Long> requirements,
            Material material,
            long requiredPayout
    ) {
        if (material == null
                || requiredPayout <= 0L
                || requiredPayout == Long.MAX_VALUE) {
            return false;
        }

        long current = requirements.getOrDefault(material, 0L);
        if (requiredPayout <= current) {
            return false;
        }

        requirements.put(material, requiredPayout);
        return true;
    }

    private void applyMinimumRequirements(
            Map<Material, Draft> drafts,
            CommodityBuild commodities,
            Map<Material, Long> requirements
    ) {
        Map<String, Long> commodityUnitFloors = new HashMap<>();

        for (Map.Entry<Material, Long> entry : requirements.entrySet()) {
            CommodityInfo info = commodities.info().get(entry.getKey());
            if (info == null) {
                continue;
            }

            long requiredUnit = divideCeiling(
                    entry.getValue(),
                    Math.max(1L, info.marketUnits())
            );
            commodityUnitFloors.merge(
                    info.marketKey(),
                    Math.max(1L, requiredUnit),
                    Math::max
            );
        }

        for (Map.Entry<String, Long> floor : commodityUnitFloors.entrySet()) {
            raiseCommodityUnitFloor(
                    drafts,
                    commodities,
                    floor.getKey(),
                    floor.getValue()
            );
        }

        for (Map.Entry<Material, Long> entry : requirements.entrySet()) {
            if (commodities.info().containsKey(entry.getKey())) {
                continue;
            }

            Draft draft = drafts.get(entry.getKey());
            if (draft == null || !draft.safe) {
                continue;
            }

            long requiredBase = requiredBaseForPayout(
                    draft,
                    entry.getValue()
            );
            if (requiredBase <= draft.baseCents) {
                continue;
            }

            draft.baseCents = requiredBase;
            draft.recipeCapped = true;
            if (!draft.curated) {
                draft.source = PriceSource.GENERATED_RECIPE;
            }
        }
    }

    private void raiseCommodityUnitFloor(
            Map<Material, Draft> drafts,
            CommodityBuild commodities,
            String marketKey,
            long requiredUnitCents
    ) {
        long unitFloor = Math.max(1L, requiredUnitCents);

        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            CommodityInfo info = entry.getValue();
            if (!info.marketKey().equals(marketKey)) {
                continue;
            }

            Draft member = drafts.get(entry.getKey());
            if (member == null || !member.safe) {
                continue;
            }

            long requiredBase = safeMultiply(
                    unitFloor,
                    Math.max(1L, info.marketUnits())
            );
            if (requiredBase > member.baseCents) {
                member.baseCents = requiredBase;
                member.recipeCapped = true;
            }
        }
    }

    private long requiredBaseForPayout(
            Draft draft,
            long requiredPayout
    ) {
        long safeRequired = Math.max(1L, requiredPayout);
        double multiplier = draft.marketEnabled
                ? draft.minimumMultiplier
                : 1.0D;

        if (!Double.isFinite(multiplier) || multiplier <= 0.0D) {
            multiplier = 1.0D;
        }

        try {
            return BigDecimal.valueOf(safeRequired)
                    .divide(
                            BigDecimal.valueOf(multiplier),
                            0,
                            RoundingMode.CEILING
                    )
                    .max(BigDecimal.ONE)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long requiredBaseForOutput(
            Draft draft,
            CommodityInfo commodityInfo,
            long requiredPayout,
            Map<Material, Long> requirements,
            CommodityBuild commodities
    ) {
        long directRequired =
                requiredBaseForPayout(
                        draft,
                        requiredPayout
                );

        if (commodityInfo == null) {
            return directRequired;
        }

        long unitFloor = 1L;
        String marketKey =
                commodityInfo.marketKey();

        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            CommodityInfo memberInfo =
                    entry.getValue();

            if (!memberInfo.marketKey().equals(
                    marketKey
            )) {
                continue;
            }

            long memberRequired =
                    requirements.getOrDefault(
                            entry.getKey(),
                            1L
                    );
            long memberUnitFloor =
                    divideCeiling(
                            Math.max(
                                    1L,
                                    memberRequired
                            ),
                            Math.max(
                                    1L,
                                    memberInfo.marketUnits()
                            )
                    );
            unitFloor = Math.max(
                    unitFloor,
                    memberUnitFloor
            );
        }

        long commodityRequired =
                safeMultiply(
                        unitFloor,
                        Math.max(
                                1L,
                                commodityInfo.marketUnits()
                        )
                );

        return Math.max(
                directRequired,
                commodityRequired
        );
    }

    private long divideCeiling(long numerator, long denominator) {
        if (numerator <= 0L || denominator <= 0L) {
            return 0L;
        }
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        return remainder == 0L
                ? quotient
                : safeAdd(quotient, 1L);
    }

    private String activationState(
            Draft draft,
            boolean variant,
            boolean sellEnabled
    ) {
        if (!sellEnabled) {
            return "V9_UNSAFE";
        }
        if (variant) {
            return "V9_VARIANT";
        }
        if (draft.source == PriceSource.GENERATED_COMMODITY) {
            return "V9_COMMODITY";
        }
        if (draft.source == PriceSource.GENERATED_RECIPE || draft.recipeCapped) {
            return "V9_RECIPE";
        }
        if (draft.source == PriceSource.CURATED) {
            return "V9_CURATED";
        }
        return "V9_FALLBACK";
    }

    private long ingredientMinimumBudget(
            RecipeSeed recipe,
            Map<Material, Draft> drafts
    ) {
        long total = 0L;

        for (IngredientChoice choice : recipe.ingredients()) {
            long cheapest = Long.MAX_VALUE;

            for (Material material : choice.materials()) {
                long value = ingredientNetMinimumPayout(
                        material,
                        drafts,
                        recipe.craftingRemainders()
                );

                if (value >= 0L) {
                    cheapest = Math.min(cheapest, value);
                }
            }

            if (cheapest == Long.MAX_VALUE) {
                return -1L;
            }

            total = safeAdd(total, cheapest);
        }

        return total;
    }

    private long ingredientNetMinimumPayout(
            Material material,
            Map<Material, Draft> drafts,
            boolean craftingRemainders
    ) {
        Draft ingredient = drafts.get(material);

        if (ingredient == null || !ingredient.safe) {
            return -1L;
        }

        long inputValue = minimumUnitPayout(ingredient);

        if (inputValue <= 0L || !craftingRemainders) {
            return inputValue;
        }

        Material remainder = material.getCraftingRemainingItem();

        if (remainder == null || remainder == Material.AIR) {
            return inputValue;
        }

        Draft remainderDraft = drafts.get(remainder);

        if (remainderDraft == null || !remainderDraft.safe) {
            return -1L;
        }

        long remainderValue = minimumUnitPayout(remainderDraft);
        return Math.max(0L, inputValue - Math.max(0L, remainderValue));
    }

    private long minimumUnitPayout(Draft draft) {
        double multiplier = draft.marketEnabled
                ? draft.minimumMultiplier
                : 1.0D;
        try {
            return BigDecimal.valueOf(draft.baseCents)
                    .multiply(BigDecimal.valueOf(multiplier))
                    .setScale(0, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long hardNoProfitUnitCeiling(
            long inputBudget,
            int outputAmount
    ) {
        if (inputBudget <= 0L || outputAmount <= 0) {
            return 0L;
        }
        return inputBudget / outputAmount;
    }

    private CommodityBuild discoverCommodities(
            List<Material> eligible,
            List<RecipeSeed> recipes,
            Map<Material, Draft> drafts
    ) {
        Map<Integer, SimpleConversion> simple = new HashMap<>();

        for (int index = 0; index < recipes.size(); index++) {
            RecipeSeed recipe = recipes.get(index);
            if (untrustedCatalogRecipe(recipe)) {
                continue;
            }
            SimpleConversion conversion = simpleConversion(recipe);
            if (conversion != null) {
                simple.put(index, conversion);
            }
        }

        Set<Integer> equivalentRecipes = new HashSet<>();
        Map<Material, List<RatioEdge>> graph = new EnumMap<>(Material.class);

        for (Map.Entry<Integer, SimpleConversion> left : simple.entrySet()) {
            for (Map.Entry<Integer, SimpleConversion> right : simple.entrySet()) {
                if (left.getKey() >= right.getKey()) {
                    continue;
                }
                SimpleConversion first = left.getValue();
                SimpleConversion second = right.getValue();

                if (first.input() != second.output()
                        || first.output() != second.input()) {
                    continue;
                }

                long leftMass = safeMultiply(
                        first.inputAmount(),
                        second.inputAmount()
                );
                long rightMass = safeMultiply(
                        first.outputAmount(),
                        second.outputAmount()
                );

                if (leftMass <= 0L || leftMass != rightMass) {
                    continue;
                }

                equivalentRecipes.add(left.getKey());
                equivalentRecipes.add(right.getKey());
                addRatioEdge(
                        graph,
                        first.input(),
                        first.output(),
                        first.inputAmount(),
                        first.outputAmount()
                );
                addRatioEdge(
                        graph,
                        first.output(),
                        first.input(),
                        first.outputAmount(),
                        first.inputAmount()
                );
            }
        }

        for (int index = 0; index < recipes.size(); index++) {
            RecipeSeed recipe = recipes.get(index);
            if (untrustedCatalogRecipe(recipe)
                    || !recipe.cooking()
                    || recipe.outputAmount() != 1
                    || recipe.ingredients().size() != 1) {
                continue;
            }
            IngredientChoice choice = recipe.ingredients().getFirst();
            if (choice.materials().size() != 1) {
                continue;
            }
            Material input = choice.materials().getFirst();
            Material output = recipe.output();

            if (!processingCommodityEligible(input, output, drafts)) {
                continue;
            }

            equivalentRecipes.add(index);
            addRatioEdge(graph, input, output, 1L, 1L);
            addRatioEdge(graph, output, input, 1L, 1L);
        }

        Map<Material, CommodityInfo> info = new EnumMap<>(Material.class);
        Set<Material> visited = EnumSet.noneOf(Material.class);
        int groups = 0;

        for (Material start : eligible) {
            if (visited.contains(start) || !graph.containsKey(start)) {
                continue;
            }

            Map<Material, Fraction> ratios = componentRatios(start, graph);
            if (ratios.size() < 2 || !consistentComponent(ratios, graph)) {
                continue;
            }

            Map<Material, Long> units = integerUnits(ratios);
            if (units.size() < 2) {
                continue;
            }

            visited.addAll(units.keySet());
            groups++;
            Material keyMaterial = units.entrySet().stream()
                    .min(
                            Comparator
                                    .comparingLong(
                                            (Map.Entry<Material, Long> entry) ->
                                                    entry.getValue()
                                    )
                                    .thenComparing(entry -> entry.getKey().name())
                    )
                    .map(Map.Entry::getKey)
                    .orElse(start);
            String key = keyMaterial.name();

            for (Map.Entry<Material, Long> entry : units.entrySet()) {
                info.put(
                        entry.getKey(),
                        new CommodityInfo(
                                key,
                                Math.max(1L, entry.getValue())
                        )
                );
            }
        }

        return new CommodityBuild(
                Map.copyOf(info),
                Set.copyOf(equivalentRecipes),
                groups
        );
    }

    private boolean processingCommodityEligible(
            Material input,
            Material output,
            Map<Material, Draft> drafts
    ) {
        Draft inputDraft = drafts.get(input);
        Draft outputDraft = drafts.get(output);
        if (inputDraft == null || outputDraft == null) {
            return false;
        }

        String inputName = input.name();
        String outputName = output.name();
        boolean resourceInput = inputName.startsWith("RAW_")
                || inputName.contains("_ORE")
                || inputName.equals("ANCIENT_DEBRIS");
        boolean processedOutput = outputName.endsWith("_INGOT")
                || outputName.equals("NETHERITE_SCRAP");

        return resourceInput
                && processedOutput
                && (inputDraft.category.equals("ores")
                || inputDraft.category.equals("nether"))
                && (outputDraft.category.equals("ores")
                || outputDraft.category.equals("nether"));
    }

    private void applyCommodityAuthority(
            Map<Material, Draft> drafts,
            CommodityBuild commodities
    ) {
        Map<String, List<Material>> groups = new LinkedHashMap<>();

        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            groups.computeIfAbsent(
                    entry.getValue().marketKey(),
                    ignored -> new ArrayList<>()
            ).add(entry.getKey());
        }

        for (List<Material> members : groups.values()) {
            Long anchorUnitCents = null;
            boolean foundCurated = false;

            for (Material material : members) {
                Draft draft = drafts.get(material);
                CommodityInfo info = commodities.info().get(material);
                if (draft == null || info == null || draft.baseCents <= 0L) {
                    continue;
                }
                if (draft.curated && !foundCurated) {
                    anchorUnitCents = null;
                    foundCurated = true;
                }
                if (foundCurated && !draft.curated) {
                    continue;
                }
                long perUnit = draft.baseCents
                        / Math.max(1L, info.marketUnits());
                perUnit = Math.max(1L, perUnit);
                if (anchorUnitCents == null || perUnit < anchorUnitCents) {
                    anchorUnitCents = perUnit;
                }
            }

            if (anchorUnitCents == null) {
                continue;
            }

            double groupMinimum = 0.0D;
            double groupMaximum = Double.POSITIVE_INFINITY;
            boolean groupDynamic = false;

            for (Material material : members) {
                Draft draft = drafts.get(material);
                if (draft == null) {
                    continue;
                }
                groupDynamic |= draft.marketEnabled;
                if (draft.marketEnabled) {
                    groupMinimum = Math.max(
                            groupMinimum,
                            draft.minimumMultiplier
                    );
                    groupMaximum = Math.min(
                            groupMaximum,
                            draft.maximumMultiplier
                    );
                }
            }

            if (!Double.isFinite(groupMaximum)) {
                groupMaximum = 1.0D;
            }
            if (!groupDynamic) {
                groupMinimum = 1.0D;
                groupMaximum = 1.0D;
            } else {
                groupMinimum = Math.clamp(groupMinimum, 0.05D, 1.0D);
                groupMaximum = Math.max(
                        1.0D,
                        Math.max(groupMinimum, groupMaximum)
                );
            }

            String marketKey = commodities.info()
                    .get(members.getFirst())
                    .marketKey();

            for (Material material : members) {
                Draft draft = drafts.get(material);
                CommodityInfo info = commodities.info().get(material);
                if (draft == null || info == null) {
                    continue;
                }

                draft.baseCents = safeMultiply(
                        anchorUnitCents,
                        info.marketUnits()
                );
                draft.marketKey = marketKey;
                draft.marketUnits = info.marketUnits();
                draft.marketEnabled = groupDynamic;
                draft.minimumMultiplier = groupDynamic
                        ? groupMinimum
                        : 1.0D;
                draft.maximumMultiplier = groupDynamic
                        ? groupMaximum
                        : 1.0D;
                if (!draft.curated) {
                    draft.source = PriceSource.GENERATED_COMMODITY;
                }
            }
        }
    }

    private void normalizeDynamicCentFloors(
            Map<Material, Draft> drafts,
            CommodityBuild commodities
    ) {
        Set<String> commodityKeys = new HashSet<>();

        for (CommodityInfo info : commodities.info().values()) {
            commodityKeys.add(info.marketKey());
        }

        for (String key : commodityKeys) {
            boolean moving = false;

            for (Map.Entry<Material, CommodityInfo> entry
                    : commodities.info().entrySet()) {
                if (!entry.getValue().marketKey().equals(key)) {
                    continue;
                }
                Draft member = drafts.get(entry.getKey());
                if (member != null && member.marketEnabled) {
                    moving = true;
                    break;
                }
            }

            if (moving) {
                setCommodityStatic(drafts, commodities, key);
            }
        }

        for (Draft draft : drafts.values()) {
            if (!draft.marketEnabled
                    || commodities.info().containsKey(draft.material)) {
                continue;
            }
            if (minimumUnitPayout(draft) <= 0L) {
                draft.marketEnabled = false;
                draft.minimumMultiplier = 1.0D;
                draft.maximumMultiplier = 1.0D;
            }
        }
    }

    private void setCommodityStatic(
            Map<Material, Draft> drafts,
            CommodityBuild commodities,
            String marketKey
    ) {
        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            if (!entry.getValue().marketKey().equals(marketKey)) {
                continue;
            }
            Draft member = drafts.get(entry.getKey());
            if (member == null) {
                continue;
            }
            member.marketEnabled = false;
            member.minimumMultiplier = 1.0D;
            member.maximumMultiplier = 1.0D;
        }
    }

    private boolean lowerCommodityUnitPrice(
            Map<Material, Draft> drafts,
            CommodityBuild commodities,
            String marketKey,
            long unitCeiling
    ) {
        boolean changed = false;

        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            CommodityInfo info = entry.getValue();
            if (!info.marketKey().equals(marketKey)) {
                continue;
            }
            Draft member = drafts.get(entry.getKey());
            if (member == null) {
                continue;
            }
            long next = safeMultiply(
                    Math.max(1L, unitCeiling),
                    Math.max(1L, info.marketUnits())
            );
            if (next < member.baseCents) {
                member.baseCents = next;
                member.recipeCapped = true;
                changed = true;
            }
        }
        return changed;
    }

    private void markCommodityUnsafe(
            Map<Material, Draft> drafts,
            CommodityBuild commodities,
            String marketKey
    ) {
        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            if (!entry.getValue().marketKey().equals(marketKey)) {
                continue;
            }
            Draft member = drafts.get(entry.getKey());
            if (member != null) {
                member.safe = false;
            }
        }
    }

    private Map<Material, Fraction> componentRatios(
            Material start,
            Map<Material, List<RatioEdge>> graph
    ) {
        Map<Material, Fraction> ratios = new EnumMap<>(Material.class);
        Queue<Material> queue = new ArrayDeque<>();
        ratios.put(start, Fraction.ONE);
        queue.add(start);

        while (!queue.isEmpty()) {
            Material current = queue.remove();
            Fraction currentRatio = ratios.get(current);

            for (RatioEdge edge : graph.getOrDefault(current, List.of())) {
                Fraction next = currentRatio.multiply(
                        edge.numerator(),
                        edge.denominator()
                );
                Fraction existing = ratios.get(edge.to());
                if (existing == null) {
                    ratios.put(edge.to(), next);
                    queue.add(edge.to());
                }
            }
        }

        return ratios;
    }

    private boolean consistentComponent(
            Map<Material, Fraction> ratios,
            Map<Material, List<RatioEdge>> graph
    ) {
        for (Map.Entry<Material, Fraction> entry : ratios.entrySet()) {
            for (RatioEdge edge : graph.getOrDefault(
                    entry.getKey(),
                    List.of()
            )) {
                Fraction target = ratios.get(edge.to());
                if (target == null) {
                    continue;
                }
                Fraction expected = entry.getValue().multiply(
                        edge.numerator(),
                        edge.denominator()
                );
                if (!expected.equals(target)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Map<Material, Long> integerUnits(
            Map<Material, Fraction> ratios
    ) {
        long commonDenominator = 1L;
        for (Fraction fraction : ratios.values()) {
            commonDenominator = lcm(
                    commonDenominator,
                    fraction.denominator()
            );
            if (commonDenominator <= 0L
                    || commonDenominator == Long.MAX_VALUE) {
                return Map.of();
            }
        }

        Map<Material, Long> raw = new EnumMap<>(Material.class);
        long divisor = 0L;
        for (Map.Entry<Material, Fraction> entry : ratios.entrySet()) {
            Fraction fraction = entry.getValue();
            long scale = commonDenominator / fraction.denominator();
            long units = safeMultiply(fraction.numerator(), scale);
            if (units <= 0L || units == Long.MAX_VALUE) {
                return Map.of();
            }
            raw.put(entry.getKey(), units);
            divisor = divisor == 0L
                    ? units
                    : gcd(divisor, units);
        }

        long commonDivisor = Math.max(1L, divisor);
        Map<Material, Long> normalized = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Long> entry : raw.entrySet()) {
            normalized.put(
                    entry.getKey(),
                    Math.max(1L, entry.getValue() / commonDivisor)
            );
        }
        return Map.copyOf(normalized);
    }

    private void addRatioEdge(
            Map<Material, List<RatioEdge>> graph,
            Material from,
            Material to,
            long numerator,
            long denominator
    ) {
        graph.computeIfAbsent(from, ignored -> new ArrayList<>())
                .add(new RatioEdge(to, numerator, denominator));
    }

    private SimpleConversion simpleConversion(RecipeSeed recipe) {
        Material input = null;
        long inputAmount = 0L;

        for (IngredientChoice choice : recipe.ingredients()) {
            if (choice.materials().size() != 1) {
                return null;
            }
            Material current = choice.materials().getFirst();

            if (recipe.craftingRemainders()
                    && current.getCraftingRemainingItem() != null) {
                return null;
            }

            if (input == null) {
                input = current;
            } else if (input != current) {
                return null;
            }
            inputAmount++;
        }

        if (input == null || input == recipe.output()) {
            return null;
        }
        return new SimpleConversion(
                input,
                inputAmount,
                recipe.output(),
                recipe.outputAmount()
        );
    }

    private List<RecipeSeed> snapshotRecipes() {
        List<RecipeSeed> recipes = new ArrayList<>();
        Iterator<Recipe> iterator = Bukkit.recipeIterator();

        while (iterator.hasNext()) {
            RecipeSeed seed = recipeSeed(iterator.next());
            if (seed != null) {
                recipes.add(seed);
            }
        }
        return List.copyOf(recipes);
    }

    @SuppressWarnings("IfCanBeSwitch")
    private RecipeSeed recipeSeed(Recipe recipe) {
        if (recipe == null) {
            return null;
        }
        ItemStack result = recipe.getResult();
        if (result.getType().isAir() || !result.getType().isItem()) {
            return null;
        }

        List<IngredientChoice> ingredients = new ArrayList<>();
        boolean cooking = false;
        boolean craftingRemainders = false;

        if (recipe instanceof ShapedRecipe shaped) {
            craftingRemainders = true;
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (int index = 0; index < row.length(); index++) {
                    char key = row.charAt(index);
                    if (key == ' ') {
                        continue;
                    }
                    IngredientChoice choice = ingredientChoice(choices.get(key));
                    if (choice == null) {
                        return null;
                    }
                    ingredients.add(choice);
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            craftingRemainders = true;
            for (RecipeChoice raw : shapeless.getChoiceList()) {
                IngredientChoice choice = ingredientChoice(raw);
                if (choice == null) {
                    return null;
                }
                ingredients.add(choice);
            }
        } else if (recipe instanceof CookingRecipe<?> cookingRecipe) {
            IngredientChoice choice = ingredientChoice(
                    cookingRecipe.getInputChoice()
            );
            if (choice == null) {
                return null;
            }
            ingredients.add(choice);
            cooking = true;
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            IngredientChoice choice = ingredientChoice(
                    stonecutting.getInputChoice()
            );
            if (choice == null) {
                return null;
            }
            ingredients.add(choice);
        } else if (recipe instanceof TransmuteRecipe transmute) {
            craftingRemainders = true;
            IngredientChoice input = ingredientChoice(transmute.getInput());
            IngredientChoice material = ingredientChoice(transmute.getMaterial());
            if (input == null || material == null) {
                return null;
            }
            ingredients.add(input);
            ingredients.add(material);
        } else if (recipe instanceof SmithingTransformRecipe smithing) {
            IngredientChoice template = ingredientChoice(smithing.getTemplate());
            IngredientChoice base = ingredientChoice(smithing.getBase());
            IngredientChoice addition = ingredientChoice(smithing.getAddition());
            if (template == null || base == null || addition == null) {
                return null;
            }
            ingredients.add(template);
            ingredients.add(base);
            ingredients.add(addition);
        } else {
            return null;
        }

        if (ingredients.isEmpty()) {
            return null;
        }
        return new RecipeSeed(
                result.getType(),
                Math.max(1, result.getAmount()),
                List.copyOf(ingredients),
                cooking,
                craftingRemainders
        );
    }

    @SuppressWarnings("UnstableApiUsage")
    private IngredientChoice ingredientChoice(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.ItemTypeChoice itemTypes) {
            List<Material> values = itemTypes.itemTypes()
                    .resolve(Registry.ITEM)
                    .stream()
                    .map(type -> Material.matchMaterial(type.getKey().toString()))
                    .filter(java.util.Objects::nonNull)
                    .filter(Material::isItem)
                    .filter(material -> material != Material.AIR)
                    .distinct()
                    .toList();
            return values.isEmpty()
                    ? null
                    : new IngredientChoice(values, false);
        }
        if (choice instanceof RecipeChoice.MaterialChoice materials) {
            List<Material> values = materials.getChoices().stream()
                    .filter(Material::isItem)
                    .filter(material -> material != Material.AIR)
                    .distinct()
                    .toList();
            return values.isEmpty()
                    ? null
                    : new IngredientChoice(values, false);
        }
        if (choice instanceof RecipeChoice.ExactChoice exact) {
            boolean untrusted = exact.getChoices().stream()
                    .anyMatch(ItemStack::hasItemMeta);
            List<Material> values = exact.getChoices().stream()
                    .map(ItemStack::getType)
                    .filter(Material::isItem)
                    .filter(material -> material != Material.AIR)
                    .distinct()
                    .toList();
            return values.isEmpty()
                    ? null
                    : new IngredientChoice(values, untrusted);
        }
        return null;
    }

    private SellCatalogSnapshot seedSnapshot() {
        Map<Material, SellCatalogEntry> entries = new EnumMap<>(Material.class);
        for (CatalogSeed seed : seeds) {
            Material material = Material.matchMaterial(seed.material());
            if (material == null) {
                continue;
            }
            entries.put(material, seed.toEntry(material));
        }
        return new SellCatalogSnapshot(
                SellPricingPolicy.CATALOG_REVISION,
                seeds.size(),
                System.currentTimeMillis(),
                Map.copyOf(entries)
        );
    }

    private void initialize(
            Connection connection,
            String table,
            String metaTable
    ) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        material VARCHAR(64) PRIMARY KEY,
                        category VARCHAR(32) NOT NULL,
                        base_price_cents BIGINT NOT NULL,
                        server_sell_enabled TINYINT(1) NOT NULL,
                        market_enabled TINYINT(1) NOT NULL,
                        market_key VARCHAR(64) NOT NULL,
                        market_units BIGINT NOT NULL,
                        target_units_per_day BIGINT NOT NULL,
                        minimum_multiplier DECIMAL(10,4) NOT NULL,
                        maximum_multiplier DECIMAL(10,4) NOT NULL,
                        buyback_multiplier DECIMAL(10,4) NOT NULL,
                        enchant_buyback_multiplier DECIMAL(10,4) NOT NULL,
                        price_source VARCHAR(32) NOT NULL,
                        auto_sell_approved TINYINT(1) NOT NULL DEFAULT 0,
                        activation_state VARCHAR(32) NOT NULL DEFAULT 'V9_UNSAFE',
                        operator_locked TINYINT(1) NOT NULL DEFAULT 0,
                        catalog_revision INT NOT NULL DEFAULT 1,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        INDEX idx_sell_items_category (category),
                        INDEX idx_sell_items_market_key (market_key),
                        INDEX idx_sell_items_sell_enabled (server_sell_enabled),
                        INDEX idx_sell_items_market_enabled (market_enabled)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(table));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        singleton_id TINYINT PRIMARY KEY,
                        catalog_revision INT NOT NULL,
                        expected_rows INT NOT NULL,
                        valid_rows INT NOT NULL,
                        sell_enabled_rows INT NOT NULL,
                        auto_approved_rows INT NOT NULL,
                        review_rows INT NOT NULL,
                        missing_rows INT NOT NULL,
                        invalid_rows INT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        updated_at BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(metaTable));
        }
    }

    private void upsertSeeds(
            Connection connection,
            String table
    ) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s (
                    material, category, base_price_cents,
                    server_sell_enabled, market_enabled, market_key,
                    market_units, target_units_per_day,
                    minimum_multiplier, maximum_multiplier,
                    buyback_multiplier, enchant_buyback_multiplier,
                    price_source, auto_sell_approved, activation_state,
                    operator_locked, catalog_revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    category = IF(operator_locked = 0, VALUES(category), category),
                    base_price_cents = IF(operator_locked = 0, VALUES(base_price_cents), base_price_cents),
                    server_sell_enabled = IF(operator_locked = 0, VALUES(server_sell_enabled), server_sell_enabled),
                    market_enabled = IF(operator_locked = 0, VALUES(market_enabled), market_enabled),
                    market_key = IF(operator_locked = 0, VALUES(market_key), market_key),
                    market_units = IF(operator_locked = 0, VALUES(market_units), market_units),
                    target_units_per_day = IF(operator_locked = 0, VALUES(target_units_per_day), target_units_per_day),
                    minimum_multiplier = IF(operator_locked = 0, VALUES(minimum_multiplier), minimum_multiplier),
                    maximum_multiplier = IF(operator_locked = 0, VALUES(maximum_multiplier), maximum_multiplier),
                    buyback_multiplier = IF(operator_locked = 0, VALUES(buyback_multiplier), buyback_multiplier),
                    enchant_buyback_multiplier = IF(operator_locked = 0, VALUES(enchant_buyback_multiplier), enchant_buyback_multiplier),
                    price_source = IF(operator_locked = 0, VALUES(price_source), price_source),
                    auto_sell_approved = IF(operator_locked = 0, VALUES(auto_sell_approved), auto_sell_approved),
                    activation_state = IF(operator_locked = 0, VALUES(activation_state), activation_state),
                    catalog_revision = IF(operator_locked = 0, VALUES(catalog_revision), catalog_revision),
                    updated_at = IF(operator_locked = 0, VALUES(updated_at), updated_at)
                """.formatted(table))) {
            for (CatalogSeed seed : seeds) {
                statement.setString(1, seed.material());
                statement.setString(2, seed.category());
                statement.setLong(3, seed.basePriceCents());
                statement.setBoolean(4, seed.serverSellEnabled());
                statement.setBoolean(5, seed.marketEnabled());
                statement.setString(6, seed.marketKey());
                statement.setLong(7, seed.marketUnits());
                statement.setLong(8, seed.targetUnitsPerDay());
                statement.setDouble(9, seed.minimumMultiplier());
                statement.setDouble(10, seed.maximumMultiplier());
                statement.setDouble(11, seed.buybackMultiplier());
                statement.setDouble(12, seed.enchantBuybackMultiplier());
                statement.setString(13, seed.priceSource());
                statement.setBoolean(14, seed.autoSellApproved());
                statement.setString(15, seed.activationState());
                statement.setBoolean(16, seed.operatorLocked());
                statement.setInt(17, seed.catalogRevision());
                statement.setLong(18, now);
                statement.setLong(19, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private DatabaseAudit audit(
            Connection connection,
            String table
    ) throws Exception {
        Map<String, CatalogRow> rows = readRows(connection, table);
        int valid = 0;
        int sellEnabled = 0;
        int operatorLocked = 0;
        int missing = 0;
        int invalid = 0;

        for (CatalogSeed seed : seeds) {
            CatalogRow row = rows.get(seed.material());
            if (row == null) {
                missing++;
                continue;
            }
            if (invalidRow(row, seed)) {
                invalid++;
                continue;
            }
            valid++;
            if (row.serverSellEnabled()) {
                sellEnabled++;
            }
            if (row.operatorLocked()) {
                operatorLocked++;
            }
        }

        return new DatabaseAudit(
                missing == 0 && invalid == 0 && valid == seeds.size(),
                seeds.size(),
                valid,
                sellEnabled,
                operatorLocked,
                missing,
                invalid
        );
    }

    private Map<String, CatalogRow> readRows(
            Connection connection,
            String table
    ) throws Exception {
        Map<String, CatalogRow> rows = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT material, category, base_price_cents,
                       server_sell_enabled, market_enabled, market_key,
                       market_units, target_units_per_day,
                       minimum_multiplier, maximum_multiplier,
                       buyback_multiplier, enchant_buyback_multiplier,
                       price_source, auto_sell_approved, activation_state,
                       operator_locked, catalog_revision
                  FROM %s
                """.formatted(table));
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String material = result.getString("material");
                if (material == null || material.isBlank()) {
                    continue;
                }
                rows.put(
                        material.toUpperCase(Locale.ROOT),
                        new CatalogRow(
                                result.getString("category"),
                                result.getLong("base_price_cents"),
                                result.getBoolean("server_sell_enabled"),
                                result.getBoolean("market_enabled"),
                                result.getString("market_key"),
                                result.getLong("market_units"),
                                result.getLong("target_units_per_day"),
                                result.getDouble("minimum_multiplier"),
                                result.getDouble("maximum_multiplier"),
                                result.getDouble("buyback_multiplier"),
                                result.getDouble("enchant_buyback_multiplier"),
                                result.getString("price_source"),
                                result.getBoolean("auto_sell_approved"),
                                result.getString("activation_state"),
                                result.getBoolean("operator_locked"),
                                result.getInt("catalog_revision")
                        )
                );
            }
        }
        return rows;
    }

    private boolean invalidRow(CatalogRow row, CatalogSeed seed) {
        if (row == null
                || row.basePriceCents() <= 0L
                || row.category() == null
                || row.category().isBlank()
                || row.marketKey() == null
                || row.marketKey().isBlank()
                || row.marketUnits() <= 0L
                || row.targetUnitsPerDay() <= 0L
                || !Double.isFinite(row.minimumMultiplier())
                || row.minimumMultiplier() <= 0.0D
                || !Double.isFinite(row.maximumMultiplier())
                || row.maximumMultiplier() < row.minimumMultiplier()
                || outsideUnitInterval(row.buybackMultiplier())
                || outsideUnitInterval(row.enchantBuybackMultiplier())
                || row.priceSource() == null
                || row.priceSource().isBlank()
                || row.activationState() == null
                || row.activationState().isBlank()) {
            return true;
        }

        if (!row.operatorLocked()) {
            return row.catalogRevision() != SellPricingPolicy.CATALOG_REVISION
                    || !sameGeneratedAuthority(row, seed);
        }

        return row.serverSellEnabled() != seed.serverSellEnabled()
                || row.basePriceCents() != seed.basePriceCents()
                || !row.category().equalsIgnoreCase(seed.category())
                || !close(row.minimumMultiplier(), seed.minimumMultiplier())
                || !close(row.maximumMultiplier(), seed.maximumMultiplier())
                || !close(row.buybackMultiplier(), seed.buybackMultiplier())
                || row.enchantBuybackMultiplier()
                > seed.enchantBuybackMultiplier() + 0.0001D
                || !row.marketKey().equalsIgnoreCase(seed.marketKey())
                || row.marketUnits() != seed.marketUnits()
                || row.autoSellApproved() != seed.autoSellApproved()
                || !row.activationState().equalsIgnoreCase(seed.activationState())
                || !row.priceSource().equalsIgnoreCase(seed.priceSource())
                || (row.marketEnabled() && !seed.marketEnabled());
    }

    private boolean sameGeneratedAuthority(
            CatalogRow row,
            CatalogSeed seed
    ) {
        return row.category().equalsIgnoreCase(seed.category())
                && row.basePriceCents() == seed.basePriceCents()
                && row.serverSellEnabled() == seed.serverSellEnabled()
                && row.marketEnabled() == seed.marketEnabled()
                && row.marketKey().equalsIgnoreCase(seed.marketKey())
                && row.marketUnits() == seed.marketUnits()
                && row.targetUnitsPerDay() == seed.targetUnitsPerDay()
                && close(row.minimumMultiplier(), seed.minimumMultiplier())
                && close(row.maximumMultiplier(), seed.maximumMultiplier())
                && close(row.buybackMultiplier(), seed.buybackMultiplier())
                && close(
                row.enchantBuybackMultiplier(),
                seed.enchantBuybackMultiplier()
        )
                && row.priceSource().equalsIgnoreCase(seed.priceSource())
                && row.autoSellApproved() == seed.autoSellApproved()
                && row.activationState().equalsIgnoreCase(seed.activationState());
    }

    private SellCatalogSnapshot loadSnapshot(
            Connection connection,
            String table
    ) throws Exception {
        Map<String, CatalogRow> rows = readRows(connection, table);
        Map<Material, SellCatalogEntry> entries = new EnumMap<>(Material.class);

        for (CatalogSeed seed : seeds) {
            CatalogRow row = rows.get(seed.material());
            if (invalidRow(row, seed)) {
                throw new IllegalStateException(
                        "Invalid Sell catalog v9 row: " + seed.material()
                );
            }
            Material material = Material.matchMaterial(seed.material());
            if (material == null) {
                throw new IllegalStateException(
                        "Unknown Sell catalog material: " + seed.material()
                );
            }
            entries.put(
                    material,
                    new SellCatalogEntry(
                            material,
                            row.basePriceCents(),
                            row.category(),
                            row.serverSellEnabled(),
                            row.marketEnabled(),
                            row.marketKey(),
                            row.marketUnits(),
                            row.targetUnitsPerDay(),
                            row.minimumMultiplier(),
                            row.maximumMultiplier(),
                            row.buybackMultiplier(),
                            row.enchantBuybackMultiplier(),
                            row.priceSource(),
                            row.autoSellApproved(),
                            row.activationState(),
                            row.operatorLocked(),
                            row.catalogRevision()
                    )
            );
        }

        return new SellCatalogSnapshot(
                SellPricingPolicy.CATALOG_REVISION,
                seeds.size(),
                System.currentTimeMillis(),
                Map.copyOf(entries)
        );
    }

    private void writeMeta(
            Connection connection,
            String metaTable,
            DatabaseAudit audit
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s (
                    singleton_id, catalog_revision, expected_rows, valid_rows,
                    sell_enabled_rows, auto_approved_rows, review_rows,
                    missing_rows, invalid_rows, status, updated_at
                ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    catalog_revision = VALUES(catalog_revision),
                    expected_rows = VALUES(expected_rows),
                    valid_rows = VALUES(valid_rows),
                    sell_enabled_rows = VALUES(sell_enabled_rows),
                    auto_approved_rows = VALUES(auto_approved_rows),
                    review_rows = VALUES(review_rows),
                    missing_rows = VALUES(missing_rows),
                    invalid_rows = VALUES(invalid_rows),
                    status = VALUES(status),
                    updated_at = VALUES(updated_at)
                """.formatted(metaTable))) {
            statement.setInt(1, SellPricingPolicy.CATALOG_REVISION);
            statement.setInt(2, audit.expectedRows());
            statement.setInt(3, audit.validRows());
            statement.setInt(4, audit.sellEnabledRows());
            statement.setInt(5, audit.sellEnabledRows());
            statement.setInt(
                    6,
                    Math.max(0, audit.expectedRows() - audit.sellEnabledRows())
            );
            statement.setInt(7, audit.missingRows());
            statement.setInt(8, audit.invalidRows());
            statement.setString(9, audit.ready() ? "READY" : "INVALID");
            statement.setLong(10, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private boolean eligibleMaterial(
            Material material,
            Set<Material> blocked
    ) {
        if (material == null
                || material == Material.AIR
                || !material.isItem()
                || blocked.contains(material)) {
            return false;
        }
        String name = material.name();
        return !name.startsWith("LEGACY_")
                && !name.startsWith("POTTED_")
                && !name.startsWith("INFESTED_")
                && !name.endsWith("_WALL_HEAD")
                && !name.endsWith("_WALL_SKULL")
                && !name.endsWith("_SPAWN_EGG")
                && !name.contains("COMMAND_BLOCK");
    }

    private Set<Material> configuredBlockedMaterials(
            FileConfiguration config
    ) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String raw : config.getStringList("settings.blocked-items")) {
            Material material = Material.matchMaterial(raw);
            if (material != null) {
                result.add(material);
            }
        }
        return Set.copyOf(result);
    }

    private long configuredMoneyCents(
            FileConfiguration config,
            String path,
            long fallback
    ) {
        Object raw = config.get(path);
        if (raw == null) {
            return Math.max(0L, fallback);
        }
        try {
            return new BigDecimal(String.valueOf(raw))
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO)
                    .longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return Math.max(0L, fallback);
        }
    }

    private String value(
            FileConfiguration configuration,
            String path,
            String fallback
    ) {
        return nonBlank(configuration.getString(path), fallback);
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }

    private String safeIdentifier(String raw) {
        String normalized = nonBlank(
                raw,
                DEFAULT_PREFIX
        ).toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_]+")
                ? normalized
                : DEFAULT_PREFIX;
    }

    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "misc";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private boolean close(double first, double second) {
        return Math.abs(first - second) <= 0.0001D;
    }

    private boolean outsideUnitInterval(double value) {
        return !Double.isFinite(value)
                || value < 0.0D
                || value > 1.0D;
    }

    private long safeAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long safeMultiply(long first, long second) {
        try {
            return Math.multiplyExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long gcd(long first, long second) {
        long a = Math.abs(first);
        long b = Math.abs(second);
        while (b != 0L) {
            long next = a % b;
            a = b;
            b = next;
        }
        return Math.max(1L, a);
    }

    private long lcm(long first, long second) {
        if (first <= 0L || second <= 0L) {
            return Long.MAX_VALUE;
        }
        long divisor = gcd(first, second);
        return safeMultiply(first / divisor, second);
    }

    private enum PriceSource {
        CURATED,
        GENERATED_COMMODITY,
        GENERATED_RECIPE,
        GENERATED_CATEGORY,
        VARIANT_REQUIRED
    }

    private static final class Draft {
        private final Material material;
        private final String category;
        private long baseCents;
        private boolean marketEnabled;
        private double minimumMultiplier;
        private double maximumMultiplier;
        private String marketKey;
        private long marketUnits;
        private final long targetUnitsPerDay;
        private PriceSource source;
        private final boolean curated;
        private double enchantBuybackMultiplier;
        private boolean safe = true;
        private boolean recipeCapped;

        private Draft(
                Material material,
                String category,
                long baseCents,
                boolean marketEnabled,
                double minimumMultiplier,
                double maximumMultiplier,
                String marketKey,
                long marketUnits,
                long targetUnitsPerDay,
                PriceSource source,
                boolean curated,
                double enchantBuybackMultiplier
        ) {
            this.material = material;
            this.category = category;
            this.baseCents = baseCents;
            this.marketEnabled = marketEnabled;
            this.minimumMultiplier = minimumMultiplier;
            this.maximumMultiplier = maximumMultiplier;
            this.marketKey = marketKey;
            this.marketUnits = marketUnits;
            this.targetUnitsPerDay = targetUnitsPerDay;
            this.source = source;
            this.curated = curated;
            this.enchantBuybackMultiplier = enchantBuybackMultiplier;
        }
    }

    private record RecipeSeed(
            Material output,
            int outputAmount,
            List<IngredientChoice> ingredients,
            boolean cooking,
            boolean craftingRemainders
    ) {
        private RecipeSeed {
            ingredients = List.copyOf(ingredients);
        }
    }

    private record IngredientChoice(
            List<Material> materials,
            boolean untrusted
    ) {
        private IngredientChoice {
            materials = List.copyOf(materials);
        }
    }

    private record SimpleConversion(
            Material input,
            long inputAmount,
            Material output,
            long outputAmount
    ) {
    }

    private record RatioEdge(
            Material to,
            long numerator,
            long denominator
    ) {
    }

    private record CommodityInfo(
            String marketKey,
            long marketUnits
    ) {
    }

    private record CommodityBuild(
            Map<Material, CommodityInfo> info,
            Set<Integer> equivalentRecipeIndexes,
            int groupCount
    ) {
        private CommodityBuild {
            info = Map.copyOf(info);
            equivalentRecipeIndexes = Set.copyOf(equivalentRecipeIndexes);
        }
    }

    private record Fraction(long numerator, long denominator) {
        private static final Fraction ONE = new Fraction(1L, 1L);

        private Fraction {
            if (denominator == 0L) {
                throw new IllegalArgumentException("denominator");
            }
            if (denominator < 0L) {
                numerator = -numerator;
                denominator = -denominator;
            }
            long commonDivisor = gcdStatic(Math.abs(numerator), denominator);
            numerator /= commonDivisor;
            denominator /= commonDivisor;
        }

        private Fraction multiply(
                long otherNumerator,
                long otherDenominator
        ) {
            if (otherDenominator <= 0L || otherNumerator <= 0L) {
                return this;
            }
            try {
                return new Fraction(
                        Math.multiplyExact(numerator, otherNumerator),
                        Math.multiplyExact(denominator, otherDenominator)
                );
            } catch (ArithmeticException exception) {
                return this;
            }
        }

        private static long gcdStatic(long first, long second) {
            long a = first;
            long b = second;
            while (b != 0L) {
                long next = a % b;
                a = b;
                b = next;
            }
            return Math.max(1L, a);
        }
    }

    private record CatalogSeed(
            String material,
            String category,
            long basePriceCents,
            boolean serverSellEnabled,
            boolean marketEnabled,
            String marketKey,
            long marketUnits,
            long targetUnitsPerDay,
            double minimumMultiplier,
            double maximumMultiplier,
            double buybackMultiplier,
            double enchantBuybackMultiplier,
            String priceSource,
            boolean autoSellApproved,
            String activationState,
            boolean operatorLocked,
            int catalogRevision
    ) {
        private SellCatalogEntry toEntry(Material materialType) {
            return new SellCatalogEntry(
                    materialType,
                    basePriceCents,
                    category,
                    serverSellEnabled,
                    marketEnabled,
                    marketKey,
                    marketUnits,
                    targetUnitsPerDay,
                    minimumMultiplier,
                    maximumMultiplier,
                    buybackMultiplier,
                    enchantBuybackMultiplier,
                    priceSource,
                    autoSellApproved,
                    activationState,
                    operatorLocked,
                    catalogRevision
            );
        }
    }

    private record CatalogBuild(
            List<CatalogSeed> seeds,
            CatalogSummary summary
    ) {
        private CatalogBuild {
            seeds = List.copyOf(seeds);
        }
    }

    private record CatalogSummary(
            int total,
            int sellEnabled,
            int curated,
            int commodity,
            int recipe,
            int fallback,
            int variant,
            int oneCent,
            int unsafe,
            int commodityGroups
    ) {
    }

    private record CatalogRow(
            String category,
            long basePriceCents,
            boolean serverSellEnabled,
            boolean marketEnabled,
            String marketKey,
            long marketUnits,
            long targetUnitsPerDay,
            double minimumMultiplier,
            double maximumMultiplier,
            double buybackMultiplier,
            double enchantBuybackMultiplier,
            String priceSource,
            boolean autoSellApproved,
            String activationState,
            boolean operatorLocked,
            int catalogRevision
    ) {
    }

    private record DatabaseAudit(
            boolean ready,
            int expectedRows,
            int validRows,
            int sellEnabledRows,
            int operatorLockedRows,
            int missingRows,
            int invalidRows
    ) {
    }
}
