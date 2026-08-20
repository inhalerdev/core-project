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
 * <p>Revision 9 makes the mechanically generated catalog the immediate runtime
 * authority and treats SQL as durable/operator-editable persistence rather than
 * an availability dependency.  Every ordinary vanilla item receives a
 * liquidation candidate.  Reversible/processing commodities conserve value,
 * one-way recipe outputs are capped against the cheapest reachable ingredient
 * payout, and only rows that cannot pay even one cent without a real crafting
 * gain remain disabled.</p>
 */
@SuppressWarnings("SqlNoDataSourceInspection")
public final class SellCatalogBootstrapService {

    private static final String DEFAULT_PREFIX = "mineacle_sell";
    private static final int DERIVATION_PASSES = 48;

    private final Core core;
    private final SellService sellService;
    private final List<CatalogSeed> seeds;
    private final CatalogSummary summary;
    private final AtomicBoolean started = new AtomicBoolean();

    /** Recipe registry access is captured on the server thread. */
    public SellCatalogBootstrapService(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;

        FileConfiguration config = YamlConfiguration.loadConfiguration(
                new File(core.getDataFolder(), "sell.yml")
        );
        Set<Material> blocked = configuredBlockedMaterials(
                config
        );
        List<RecipeSeed> recipes = snapshotRecipes();
        CatalogBuild build = buildCatalog(
                config,
                blocked,
                recipes
        );
        this.seeds = build.seeds();
        this.summary = build.summary();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        /*
         * SQL availability must never decide whether ordinary vanilla items
         * have liquidity.  Activate the exact same mechanically audited seed
         * snapshot immediately; SQL may later apply only graph-safe operator overrides.
         */
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
                        + summary.unsafe() + " mechanically unavailable"
        );

        core.getServer().getScheduler().runTaskAsynchronously(
                core,
                this::bootstrapSql
        );
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
                            + summary.recipe() + " recipe-capped, "
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
                            curated ? PriceSource.CURATED : PriceSource.GENERATED_CATEGORY,
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

        Map<Material, List<IndexedRecipe>> byOutput = new EnumMap<>(Material.class);
        for (int index = 0; index < recipes.size(); index++) {
            if (commodities.equivalentRecipeIndexes().contains(index)) {
                continue;
            }
            RecipeSeed recipe = recipes.get(index);
            byOutput.computeIfAbsent(
                    recipe.output(),
                    ignored -> new ArrayList<>()
            ).add(new IndexedRecipe(index, recipe));
        }

        /*
         * Recipe caps are monotonic-decreasing.  Every pass can only reduce an
         * output, so cyclic one-way graphs converge without ever temporarily
         * authorizing an unsafe higher price.
         */
        for (int pass = 0; pass < DERIVATION_PASSES; pass++) {
            boolean changed = false;

            for (Map.Entry<Material, List<IndexedRecipe>> entry : byOutput.entrySet()) {
                Material output = entry.getKey();
                Draft draft = drafts.get(output);

                if (draft == null
                        || SellVariantValuationService.supportsMaterial(output)) {
                    continue;
                }

                CommodityInfo commodityInfo =
                        commodities.info().get(output);
                boolean commodityOutput =
                        commodityInfo != null;
                double outputMaximum =
                        commodityOutput && draft.marketEnabled
                                ? draft.maximumMultiplier
                                : 1.0D;
                long safeCeiling = Long.MAX_VALUE;
                boolean auditedRecipe = false;

                for (IndexedRecipe indexed : entry.getValue()) {
                    long inputBudget = ingredientMinimumBudget(
                            indexed.recipe(),
                            drafts
                    );

                    if (inputBudget < 0L) {
                        auditedRecipe = true;
                        safeCeiling = 0L;
                        break;
                    }

                    auditedRecipe = true;
                    long retainedCeiling =
                            SellPricingPolicy.outputBaseCeilingCents(
                                    inputBudget,
                                    indexed.recipe().outputAmount(),
                                    outputMaximum
                            );

                    if (retainedCeiling <= 0L) {
                        /*
                         * Preserve a cent only when direct 100% conservation
                         * still proves that cent cannot create server cash.
                         */
                        retainedCeiling = hardNoProfitUnitCeiling(
                                inputBudget,
                                indexed.recipe().outputAmount()
                        );
                    }

                    safeCeiling = Math.min(
                            safeCeiling,
                            retainedCeiling
                    );
                }

                if (!auditedRecipe) {
                    continue;
                }

                if (safeCeiling <= 0L) {
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
                    long unitCeiling =
                            safeCeiling
                                    / Math.max(
                                    1L,
                                    commodityInfo.marketUnits()
                            );

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
                    draft.marketEnabled = false;
                    draft.minimumMultiplier = 1.0D;
                    draft.maximumMultiplier = 1.0D;

                    long next = draft.curated
                            ? Math.clamp(
                            draft.baseCents,
                            1L,
                            safeCeiling
                    )
                            : safeCeiling;

                    if (next != draft.baseCents) {
                        draft.baseCents = next;
                        changed = true;
                    }
                }

                if (draft.source != PriceSource.CURATED) {
                    draft.source = PriceSource.GENERATED_RECIPE;
                } else {
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
                            draft.marketEnabled ? draft.minimumMultiplier : 1.0D,
                            draft.marketEnabled ? draft.maximumMultiplier : 1.0D,
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
            if (choice.untrusted()) {
                return -1L;
            }

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
            /*
             * A returned crafting container that does not have a verified
             * liquidation value makes the net ingredient cost unknowable.
             * Fail this recipe path closed instead of pretending the returned
             * item was consumed.
             */
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
            SimpleConversion conversion = simpleConversion(recipes.get(index));
            if (conversion != null) {
                simple.put(index, conversion);
            }
        }

        Set<Integer> equivalentRecipes = new HashSet<>();
        Map<Material, List<RatioEdge>> graph = new EnumMap<>(Material.class);

        /* Exact reversible crafting pairs. */
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

        /*
         * Ore/raw-material smelting is one-way but should not create value.
         * Link 1:1 processing forms to the same commodity so market movement
         * cancels out exactly and smelting cannot become a price multiplier.
         */
        for (int index = 0; index < recipes.size(); index++) {
            RecipeSeed recipe = recipes.get(index);
            if (!recipe.cooking()
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
                                    .comparingLong((Map.Entry<Material, Long> entry) -> entry.getValue())
                                    .thenComparing(entry -> entry.getKey().name())
                    )
                    .map(Map.Entry::getKey)
                    .orElse(start);
            String key = keyMaterial.name();

            for (Map.Entry<Material, Long> entry : units.entrySet()) {
                info.put(
                        entry.getKey(),
                        new CommodityInfo(key, Math.max(1L, entry.getValue()))
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

        for (Map.Entry<Material, CommodityInfo> entry : commodities.info().entrySet()) {
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
                long perUnit =
                        draft.baseCents
                                / Math.max(
                                1L,
                                info.marketUnits()
                        );
                perUnit = Math.max(1L, perUnit);
                if (anchorUnitCents == null
                        || perUnit < anchorUnitCents) {
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
                    groupMinimum = Math.max(groupMinimum, draft.minimumMultiplier);
                    groupMaximum = Math.min(groupMaximum, draft.maximumMultiplier);
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
                groupMaximum = Math.max(1.0D, Math.max(groupMinimum, groupMaximum));
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
                draft.minimumMultiplier = groupDynamic ? groupMinimum : 1.0D;
                draft.maximumMultiplier = groupDynamic ? groupMaximum : 1.0D;
                if (!draft.curated) {
                    draft.source = PriceSource.GENERATED_COMMODITY;
                }
            }
        }
    }

    /**
     * A moving multiplier is only useful when the lowest allowed market state
     * still pays at least one cent.  Do not raise individual commodity members
     * to achieve that: doing so would destroy exact reversible ratios.  Instead
     * make the entire reversible family static.  Non-commodity rows are made
     * static independently.
     *
     * <p>Reversible commodity families are also intentionally static in v9.
     * SellService currently rounds each Material payout independently after the
     * shared multiplier.  Independent cent rounding can otherwise make a
     * nugget -> ingot -> nugget cycle gain a few cents even when base values are
     * perfectly proportional. Static family pricing is exact and fail-closed;
     * primary non-reversible farm/resource commodities remain dynamic.</p>
     */
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

            if (!moving) {
                continue;
            }

            /*
             * See method Javadoc: exact reversible value takes precedence over
             * market motion until runtime pricing operates on shared commodity
             * units rather than independently rounded Material prices.
             */
            setCommodityStatic(drafts, commodities, key);
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
            for (RatioEdge edge : graph.getOrDefault(entry.getKey(), List.of())) {
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

    private Map<Material, Long> integerUnits(Map<Material, Fraction> ratios) {
        long commonDenominator = 1L;
        for (Fraction fraction : ratios.values()) {
            commonDenominator = lcm(commonDenominator, fraction.denominator());
            if (commonDenominator <= 0L || commonDenominator == Long.MAX_VALUE) {
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
            divisor = divisor == 0L ? units : gcd(divisor, units);
        }

        long gcd = Math.max(1L, divisor);
        Map<Material, Long> normalized = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Long> entry : raw.entrySet()) {
            normalized.put(entry.getKey(), Math.max(1L, entry.getValue() / gcd));
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
            if (choice.untrusted() || choice.materials().size() != 1) {
                return null;
            }
            Material current = choice.materials().getFirst();

            if (recipe.craftingRemainders()
                    && current.getCraftingRemainingItem() != null) {
                /*
                 * A conversion that returns a crafting container is not an
                 * exact reversible value edge even if another recipe points
                 * back to the input Material. Keep it in the one-way recipe
                 * audit where the remainder is explicitly subtracted.
                 */
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
            IngredientChoice choice = ingredientChoice(cookingRecipe.getInputChoice());
            if (choice == null) {
                return null;
            }
            ingredients.add(choice);
            cooking = true;
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            IngredientChoice choice = ingredientChoice(stonecutting.getInputChoice());
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

    private boolean invalidRow(
            CatalogRow row,
            CatalogSeed seed
    ) {
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
            return row.catalogRevision()
                    != SellPricingPolicy.CATALOG_REVISION
                    || !sameGeneratedAuthority(
                    row,
                    seed
            );
        }

        /*
         * A locked row cannot alter clean-item liquidation authority in
         * isolation. Lowering or disabling an ingredient while leaving its
         * crafted outputs unchanged can create a server-cash conversion path.
         * Operator locks may only make market movement more conservative,
         * alter the demand target, or lower enchant liquidation.
         */
        return row.serverSellEnabled() != seed.serverSellEnabled()
                || row.basePriceCents() != seed.basePriceCents()
                || !row.category().equalsIgnoreCase(seed.category())
                || !close(
                row.minimumMultiplier(),
                seed.minimumMultiplier()
        )
                || !close(
                row.maximumMultiplier(),
                seed.maximumMultiplier()
        )
                || !close(
                row.buybackMultiplier(),
                seed.buybackMultiplier()
        )
                || row.enchantBuybackMultiplier()
                > seed.enchantBuybackMultiplier() + 0.0001D
                || !row.marketKey().equalsIgnoreCase(seed.marketKey())
                || row.marketUnits() != seed.marketUnits()
                || row.autoSellApproved() != seed.autoSellApproved()
                || !row.activationState().equalsIgnoreCase(seed.activationState())
                || !row.priceSource().equalsIgnoreCase(seed.priceSource())
                || (row.marketEnabled() && !seed.marketEnabled());
    }

    private boolean sameGeneratedAuthority(CatalogRow row, CatalogSeed seed) {
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
                && close(row.enchantBuybackMultiplier(), seed.enchantBuybackMultiplier())
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

    private boolean eligibleMaterial(Material material, Set<Material> blocked) {
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
        for (String raw : config.getStringList(
                "settings.blocked-items"
        )) {
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
        return value == null || value.isBlank() ? fallback : value.trim();
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

    private record IndexedRecipe(int index, RecipeSeed recipe) {
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

    private record CommodityInfo(String marketKey, long marketUnits) {
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
            long gcd = gcdStatic(Math.abs(numerator), denominator);
            numerator /= gcd;
            denominator /= gcd;
        }

        private Fraction multiply(long otherNumerator, long otherDenominator) {
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
