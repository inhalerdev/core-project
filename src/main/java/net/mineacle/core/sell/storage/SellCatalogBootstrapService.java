package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import net.mineacle.core.sell.model.SellCatalogEntry;
import net.mineacle.core.sell.model.SellCatalogSnapshot;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
 * v1.0.45 Sell/Worth variant-safe catalog + runtime authority bootstrap.
 *
 * <p>Revision 5 adds worst-case recipe arbitrage ceilings and safely activates
 * legitimate non-recipe survival items at a one-cent floor. Revision 6 also
 * activates supported metadata-sensitive vanilla variants at a fixed one-cent
 * runtime floor. Revision 7 resolves additional recipe outputs through a
 * fixed-point server-buyback audit. Revision 8 turns every automatic safety
 * floor into a strict server-cash invariant: one cent per accepted item,
 * market-disabled, fixed 1.0x, with no category/enchantment buyback applied.
 * Operator-locked rows remain explicit operator authority.</p>
 *
 * <p>If SQL, migration, audit, snapshot loading, or runtime activation fails,
 * SellService keeps the known-safe YAML definitions for that boot.</p>
 */
@SuppressWarnings("SqlNoDataSourceInspection")
public final class SellCatalogBootstrapService {

    private static final String DEFAULT_PREFIX = "mineacle_sell";
    private static final int CATALOG_REVISION = 8;
    private static final long UNTRUSTED_FLOOR_CENTS = 1L;
    private static final double RECIPE_HAIRCUT = 0.70D;
    private static final int DERIVATION_PASSES = 16;

    /*
     * These Materials are metadata-sensitive, so recipe derivation does not
     * invent per-variant values. Revision 6+ activates legitimate variants at
     * the strict one-cent safety floor and keeps their market multiplier fixed
     * at 1.0x.
     */
    private static final Set<Material> RUNTIME_VARIANT_MATERIALS =
            EnumSet.of(
                    Material.POTION,
                    Material.SPLASH_POTION,
                    Material.LINGERING_POTION,
                    Material.TIPPED_ARROW,
                    Material.SUSPICIOUS_STEW,
                    Material.FIREWORK_ROCKET,
                    Material.FIREWORK_STAR,
                    Material.WRITTEN_BOOK,
                    Material.FILLED_MAP,
                    Material.GOAT_HORN
            );

    private final Core core;
    private final SellService sellService;
    private final List<CatalogSeed> seeds;
    private final CatalogSummary summary;
    private final AtomicBoolean started =
            new AtomicBoolean();

    /**
     * Constructor runs on the server thread from SellModule.enable().
     * Recipe registry access stays here; JDBC never runs here.
     */
    public SellCatalogBootstrapService(
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

        Set<Material> blocked =
                configuredBlockedMaterials(
                        sellConfig
                );

        List<RecipeSeed> recipes =
                snapshotRecipes();
        Set<Material> allRecipeOutputs =
                snapshotRecipeOutputs();
        Set<Material> unsupportedRecipeOutputs =
                snapshotUnsupportedRecipeOutputs();

        CatalogBuild build =
                buildCatalog(
                        sellService,
                        sellConfig,
                        blocked,
                        recipes,
                        allRecipeOutputs,
                        unsupportedRecipeOutputs
                );

        this.seeds = build.seeds();
        this.summary = build.summary();
    }

    public void start() {
        if (!started.compareAndSet(
                false,
                true
        )) {
            return;
        }

        core.getServer()
                .getScheduler()
                .runTaskAsynchronously(
                        core,
                        this::bootstrap
                );
    }

    private void bootstrap() {
        try {
            FileConfiguration sellConfig =
                    YamlConfiguration.loadConfiguration(
                            new File(
                                    core.getDataFolder(),
                                    "sell.yml"
                            )
                    );

            String storage =
                    nonBlank(
                            sellConfig.getString(
                                    "market.storage",
                                    "mysql"
                            ),
                            "mysql"
                    );

            if (!storage.equalsIgnoreCase("mysql")
                    && !storage.equalsIgnoreCase(
                    "mariadb"
            )) {
                core.getLogger().warning(
                        "Sell catalog database migration skipped — "
                                + "market.storage is not mysql/mariadb"
                );
                return;
            }

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

            FileConfiguration databaseConfig =
                    YamlConfiguration.loadConfiguration(
                            databaseFile
                    );

            String driverClass =
                    value(
                            databaseConfig,
                            "database.driver-class",
                            "com.mysql.cj.jdbc.Driver"
                    );
            String jdbcUrl =
                    value(
                            databaseConfig,
                            "database.jdbc-url",
                            "jdbc:mysql://127.0.0.1:3306/mineacle"
                    );
            String username =
                    value(
                            databaseConfig,
                            "database.username",
                            "mineacle_core"
                    );
            String password =
                    value(
                            databaseConfig,
                            "database.password",
                            ""
                    );

            String prefix =
                    safeIdentifier(
                            sellConfig.getString(
                                    "market.table-prefix",
                                    DEFAULT_PREFIX
                            )
                    );
            String table =
                    prefix + "_items";
            String metaTable =
                    prefix + "_catalog_meta";

            Class.forName(driverClass);

            DatabaseAudit audit;
            SellCatalogSnapshot runtimeSnapshot;

            try (Connection connection =
                         DriverManager.getConnection(
                                 jdbcUrl,
                                 username,
                                 password
                         )) {
                initialize(
                        connection,
                        table
                );
                migrateColumns(
                        connection,
                        table
                );
                seed(
                        connection,
                        table
                );
                initializeMeta(
                        connection,
                        metaTable
                );
                audit = audit(
                        connection,
                        table
                );
                writeMeta(
                        connection,
                        metaTable,
                        audit
                );

                runtimeSnapshot =
                        audit.ready()
                                ? loadRuntimeSnapshot(
                                connection,
                                table,
                                metaTable
                        )
                                : null;
            }

            if (!audit.ready()) {
                throw new IllegalStateException(
                        "Sell catalog v"
                                + CATALOG_REVISION
                                + " failed readiness audit: "
                                + audit.missingRows()
                                + " missing, "
                                + audit.invalidRows()
                                + " invalid"
                );
            }

            SellCatalogSnapshot readySnapshot =
                    runtimeSnapshot;

            core.getServer()
                    .getScheduler()
                    .runTask(
                            core,
                            () -> {
                                if (!core.isEnabled()) {
                                    return;
                                }

                                if (!sellService
                                        .activateCatalogSnapshot(
                                                readySnapshot
                                        )) {
                                    core.getLogger().warning(
                                            "Sell catalog was READY in SQL "
                                                    + "but runtime activation "
                                                    + "was rejected — YAML "
                                                    + "fallback remains active"
                                    );
                                }
                            }
                    );

            core.getLogger().info(
                    "Sell catalog v"
                            + CATALOG_REVISION
                            + " READY — "
                            + audit.validRows()
                            + "/"
                            + audit.expectedRows()
                            + " rows valid, "
                            + audit.sellEnabledRows()
                            + " sell-enabled, "
                            + audit.autoApprovedRows()
                            + " auto-approved, "
                            + audit.reviewRows()
                            + " review-only — "
                            + summary.total()
                            + " materials — "
                            + summary.curated()
                            + " curated, "
                            + summary.commodityGenerated()
                            + " commodity-derived, "
                            + summary.recipeGenerated()
                            + " recipe-derived, "
                            + summary.categoryGenerated()
                            + " category-derived, "
                            + summary.variantSafe()
                            + " variant-safe, "
                            + summary.floorRecipeSafe()
                            + " floor-recipe-safe, "
                            + summary.arbitrageReview()
                            + " arbitrage-review, "
                            + summary.commodityGroups()
                            + " reversible commodity groups"
            );
        } catch (Exception exception) {
            /*
             * Catalog readiness is still not live pricing authority.
             * Any SQL or audit failure therefore leaves all gameplay payouts
             * on the already-working YAML/runtime snapshot.
             */
            core.getLogger().log(
                    Level.WARNING,
                    "Could not migrate Sell item catalog database — "
                            + "live Sell pricing remains unchanged",
                    exception
            );
        }
    }

    private CatalogBuild buildCatalog(
            SellService sellService,
            FileConfiguration config,
            Set<Material> blocked,
            List<RecipeSeed> recipes,
            Set<Material> allRecipeOutputs,
            Set<Material> unsupportedRecipeOutputs
    ) {
        List<Material> eligible =
                new ArrayList<>();

        for (Material material
                : Material.values()) {
            if (!eligibleMaterial(
                    material,
                    blocked
            )) {
                continue;
            }

            eligible.add(material);
        }

        eligible.sort(
                Comparator.comparing(
                        Material::name
                )
        );

        Map<Material, Long> prices =
                new EnumMap<>(
                        Material.class
                );
        Map<Material, PriceSource> sources =
                new EnumMap<>(
                        Material.class
                );
        Set<Material> explicit =
                EnumSet.noneOf(
                        Material.class
                );

        for (Material material : eligible) {
            long current =
                    sellService.baseWorthCents(
                            material
                    );

            if (current > 0L
                    && sellService
                    .isExplicitlyPriced(
                            material
                    )) {
                prices.put(
                        material,
                        current
                );
                sources.put(
                        material,
                        PriceSource.CURATED
                );
                explicit.add(material);
                continue;
            }

            /*
             * Unknown/unreviewed items begin at one cent, never at a broad
             * category value. Recipe/commodity derivation may replace this
             * with a stronger candidate later.
             *
             * This avoids a dangerous case where an arbitrary equipment or
             * rare-item fallback becomes a meaningful future payout simply
             * because the material had no curated row yet.
             */
            prices.put(
                    material,
                    UNTRUSTED_FLOOR_CENTS
            );
            sources.put(
                    material,
                    PriceSource.GENERATED_CATEGORY
            );
        }

        CommodityBuild commodities =
                discoverCommodities(
                        eligible,
                        recipes
                );

        applyCommodityPrices(
                prices,
                sources,
                explicit,
                commodities
        );

        Set<Integer> reversibleRecipes =
                commodities
                        .reversibleRecipeIndexes();

        /*
         * Lower generated candidates to a conservative percentage of the
         * cheapest known ingredient path. Curated anchors are not rewritten.
         *
         * The minimum recipe path is deliberately used. If oak OR bamboo can
         * craft an item, the cheapest legal path is the one that matters for
         * anti-arbitrage.
         */
        for (int pass = 0;
             pass < DERIVATION_PASSES;
             pass++) {
            boolean changed = false;

            for (int index = 0;
                 index < recipes.size();
                 index++) {
                if (reversibleRecipes.contains(
                        index
                )) {
                    continue;
                }

                RecipeSeed recipe =
                        recipes.get(index);
                Material output =
                        recipe.output();

                if (!prices.containsKey(output)
                        || explicit.contains(output)
                        || RUNTIME_VARIANT_MATERIALS.contains(
                        output
                )) {
                    continue;
                }

                long ingredientTotal =
                        ingredientCost(
                                recipe,
                                prices,
                                sources
                        );

                if (ingredientTotal <= 0L
                        || ingredientTotal
                        == Long.MAX_VALUE) {
                    continue;
                }

                long derived =
                        recipeUnitValue(
                                ingredientTotal,
                                recipe.outputAmount()
                        );

                if (derived <= 0L) {
                    continue;
                }

                long current =
                        prices.get(output);
                PriceSource currentSource =
                        sources.getOrDefault(
                                output,
                                PriceSource.GENERATED_CATEGORY
                        );

                /*
                 * A one-cent GENERATED_CATEGORY value is a placeholder, not
                 * an upper bound. The previous revision only accepted a
                 * derived value when it was lower than that placeholder,
                 * which prevented almost every safe recipe price from ever
                 * being generated.
                 */
                if (currentSource
                        == PriceSource.GENERATED_CATEGORY
                        || derived < current) {
                    prices.put(
                            output,
                            derived
                    );
                    sources.put(
                            output,
                            PriceSource.GENERATED_RECIPE
                    );
                    changed = true;
                }
            }

            if (!changed) {
                break;
            }
        }

        /*
         * Re-apply commodity equivalence after recipe derivation. If a
         * non-curated member was lowered through another recipe, all generated
         * forms in its reversible pool follow the safest normalized unit.
         */
        applyCommodityPrices(
                prices,
                sources,
                explicit,
                commodities
        );

        /*
         * A recipe-derived base value can still become unsafe if the output
         * market reaches its maximum multiplier while ingredient markets are
         * at their minimums. Revision 5 applies a worst-case ceiling so a
         * normal crafting conversion cannot manufacture additional server
         * buyback value merely by waiting for divergent market conditions.
         */
        Set<Material> dynamicArbitrageUnsafe =
                applyDynamicArbitrageCeilings(
                        sellService,
                        config,
                        prices,
                        sources,
                        explicit,
                        recipes,
                        reversibleRecipes
                );

        Set<Material> recipeFloorSafe =
                resolveRecipeFloorSafeMaterials(
                        sellService,
                        config,
                        eligible,
                        prices,
                        sources,
                        explicit,
                        recipes,
                        reversibleRecipes,
                        allRecipeOutputs,
                        unsupportedRecipeOutputs,
                        dynamicArbitrageUnsafe
                );

        Set<Material> arbitrageUnsafe =
                EnumSet.noneOf(
                        Material.class
                );
        arbitrageUnsafe.addAll(
                dynamicArbitrageUnsafe
        );
        arbitrageUnsafe.removeAll(
                recipeFloorSafe
        );

        List<CatalogSeed> result =
                new ArrayList<>();

        int curatedCount = 0;
        int commodityCount = 0;
        int recipeCount = 0;
        int categoryCount = 0;
        int variantCount = 0;
        int floorRecipeCount = 0;

        for (Material material : eligible) {
            String category =
                    normalizeCategory(
                            sellService.category(
                                    material
                            )
                    );
            PriceSource source =
                    sources.getOrDefault(
                            material,
                            PriceSource.GENERATED_CATEGORY
                    );

            CommodityInfo commodity =
                    commodities.info()
                            .getOrDefault(
                                    material,
                                    new CommodityInfo(
                                            material.name(),
                                            1L
                                    )
                            );

            String itemPath =
                    "prices."
                            + material.name();

            double minimumMultiplier =
                    clamp(
                            config.getDouble(
                                    itemPath
                                            + ".minimum-multiplier",
                                    config.getDouble(
                                            "market.minimum-multiplier",
                                            0.35D
                                    )
                            ),
                            0.01D,
                            100.0D
                    );

            double maximumMultiplier =
                    clamp(
                            config.getDouble(
                                    itemPath
                                            + ".maximum-multiplier",
                                    config.getDouble(
                                            "market.maximum-multiplier",
                                            1.75D
                                    )
                            ),
                            minimumMultiplier,
                            100.0D
                    );

            String categoryPath =
                    "valuation.category-buyback."
                            + category;

            double categoryBuyback =
                    clamp(
                            config.getDouble(
                                    categoryPath + ".base",
                                    1.0D
                            ),
                            0.0D,
                            1.0D
                    );

            double buyback =
                    clamp(
                            config.getDouble(
                                    itemPath
                                            + ".buyback-multiplier",
                                    categoryBuyback
                            ),
                            0.0D,
                            1.0D
                    );

            double categoryEnchantBuyback =
                    clamp(
                            config.getDouble(
                                    categoryPath
                                            + ".enchants",
                                    categoryBuyback
                            ),
                            0.0D,
                            1.0D
                    );

            double enchantBuyback =
                    clamp(
                            config.getDouble(
                                    itemPath
                                            + ".enchant-buyback-multiplier",
                                    categoryEnchantBuyback
                            ),
                            0.0D,
                            1.0D
                    );

            boolean variantRequired =
                    RUNTIME_VARIANT_MATERIALS.contains(
                            material
                    );

            boolean currentTrustedSell =
                    explicit.contains(material)
                            && sellService
                            .isServerSellableMaterial(
                                    material
                            );

            boolean generatedSafe =
                    source == PriceSource.GENERATED_RECIPE
                            || source
                            == PriceSource.GENERATED_COMMODITY;

            /*
             * Natural/non-recipe survival items are safe to expose at the
             * one-cent floor because no normal crafting path can multiply a
             * cheaper Sell input into them. Recipe outputs must either have a
             * trusted derived/commodity value or remain review-only.
             */
            boolean categoryFloorSafe =
                    source == PriceSource.GENERATED_CATEGORY
                            && !allRecipeOutputs.contains(
                            material
                    );
            boolean recipeFloorApproved =
                    recipeFloorSafe.contains(
                            material
                    );
            boolean recipeArbitrageUnsafe =
                    arbitrageUnsafe.contains(
                            material
                    );

            boolean autoSellApproved =
                    variantRequired
                            ? buyback > 0.0D
                            : !recipeArbitrageUnsafe
                            && buyback > 0.0D
                            && (currentTrustedSell
                            || generatedSafe
                            || categoryFloorSafe
                            || recipeFloorApproved);

            String activationState =
                    activationState(
                            source,
                            currentTrustedSell,
                            autoSellApproved,
                            variantRequired,
                            categoryFloorSafe,
                            recipeFloorApproved,
                            recipeArbitrageUnsafe
                    );

            boolean categoryMarketEnabled =
                    config.getBoolean(
                            "market.categories."
                                    + category
                                    + ".enabled",
                            defaultMarketEnabled(
                                    category
                            )
                    );

            boolean safetyFloorState =
                    safetyFloorActivationState(
                            activationState
                    );

            boolean marketEnabled =
                    autoSellApproved
                            && !safetyFloorState
                            && config.getBoolean(
                            itemPath
                                    + ".market-enabled",
                            categoryMarketEnabled
                    );

            double effectiveMinimumMultiplier =
                    safetyFloorState
                            ? 1.0D
                            : minimumMultiplier;
            double effectiveMaximumMultiplier =
                    safetyFloorState
                            ? 1.0D
                            : maximumMultiplier;
            double effectiveBuyback =
                    safetyFloorState
                            ? 1.0D
                            : buyback;
            double effectiveEnchantBuyback =
                    safetyFloorState
                            ? 0.0D
                            : enchantBuyback;

            long candidateBaseCents =
                    safetyFloorState
                            ? UNTRUSTED_FLOOR_CENTS
                            : Math.max(
                            1L,
                            prices.getOrDefault(
                                    material,
                                    1L
                            )
                    );

            /*
             * A future recipe/config addition must never make an approved row
             * round down to a zero-cent one-item payout.
             *
             * A generated recipe that already passed the worst-case
             * arbitrage audit can safely be lowered to the fixed one-cent
             * recipe floor. Other contradictory rows fail closed to review
             * rather than making the entire catalog internally inconsistent.
             */
            if (autoSellApproved
                    && minimumServerUnitCents(
                    candidateBaseCents,
                    marketEnabled,
                    effectiveMinimumMultiplier,
                    effectiveBuyback
            ) <= 0L) {
                if (source
                        == PriceSource.GENERATED_RECIPE) {
                    activationState =
                            "READY_FLOOR_RECIPE";
                    marketEnabled = false;
                    candidateBaseCents =
                            UNTRUSTED_FLOOR_CENTS;
                    effectiveMinimumMultiplier =
                            1.0D;
                    effectiveMaximumMultiplier =
                            1.0D;
                    effectiveBuyback =
                            1.0D;
                    effectiveEnchantBuyback =
                            0.0D;
                } else {
                    autoSellApproved = false;
                    activationState =
                            "REVIEW_ZERO_PAYOUT";
                    marketEnabled = false;
                }
            }

            long target =
                    Math.max(
                            1L,
                            config.getLong(
                                    itemPath
                                            + ".target-units-per-day",
                                    config.getLong(
                                            "market.targets."
                                                    + category,
                                            1_000L
                                    )
                            )
                    );

            if ("READY_FLOOR_RECIPE".equals(
                    activationState
            )) {
                floorRecipeCount++;
            }

            if (variantRequired) {
                source =
                        PriceSource.VARIANT_SAFE;
                variantCount++;
            } else {
                switch (source) {
                    case CURATED ->
                            curatedCount++;
                    case GENERATED_COMMODITY ->
                            commodityCount++;
                    case GENERATED_RECIPE ->
                            recipeCount++;
                    case GENERATED_CATEGORY ->
                            categoryCount++;
                    case VARIANT_SAFE -> {
                        // handled above
                    }
                }
            }

            result.add(
                    new CatalogSeed(
                            material.name(),
                            category,
                            candidateBaseCents,
                            autoSellApproved,
                            marketEnabled,
                            commodity.marketKey(),
                            Math.max(
                                    1L,
                                    commodity.marketUnits()
                            ),
                            target,
                            effectiveMinimumMultiplier,
                            effectiveMaximumMultiplier,
                            effectiveBuyback,
                            effectiveEnchantBuyback,
                            source.name(),
                            autoSellApproved,
                            activationState,
                            false,
                            CATALOG_REVISION
                    )
            );
        }

        CatalogSummary summary =
                new CatalogSummary(
                        result.size(),
                        curatedCount,
                        commodityCount,
                        recipeCount,
                        categoryCount,
                        variantCount,
                        floorRecipeCount,
                        arbitrageUnsafe.size(),
                        commodities.groupCount()
                );

        return new CatalogBuild(
                List.copyOf(result),
                summary
        );
    }

    private List<RecipeSeed> snapshotRecipes() {
        List<RecipeSeed> recipes =
                new ArrayList<>();

        Iterator<Recipe> iterator =
                Bukkit.recipeIterator();

        while (iterator.hasNext()) {
            Recipe recipe =
                    iterator.next();
            RecipeSeed seed =
                    recipeSeed(recipe);

            if (seed != null) {
                recipes.add(seed);
            }
        }

        return List.copyOf(recipes);
    }

    private Set<Material> snapshotRecipeOutputs() {
        Set<Material> outputs =
                EnumSet.noneOf(
                        Material.class
                );
        Iterator<Recipe> iterator =
                Bukkit.recipeIterator();

        while (iterator.hasNext()) {
            Recipe recipe =
                    iterator.next();

            if (recipe == null) {
                continue;
            }

            Material output =
                    recipe.getResult()
                            .getType();

            if (output != Material.AIR
                    && output.isItem()) {
                outputs.add(output);
            }
        }

        return Set.copyOf(outputs);
    }

    private Set<Material> snapshotUnsupportedRecipeOutputs() {
        Set<Material> outputs =
                EnumSet.noneOf(
                        Material.class
                );
        Iterator<Recipe> iterator =
                Bukkit.recipeIterator();

        while (iterator.hasNext()) {
            Recipe recipe =
                    iterator.next();

            ItemStack result =
                    recipe.getResult();
            Material output =
                    result.getType();

            if (output == Material.AIR
                    || !output.isItem()) {
                continue;
            }

            if (recipeSeed(recipe) == null) {
                outputs.add(output);
            }
        }

        return Set.copyOf(outputs);
    }

    @SuppressWarnings("IfCanBeSwitch")
    private RecipeSeed recipeSeed(
            Recipe recipe
    ) {
        if (recipe == null) {
            return null;
        }

        ItemStack result =
                recipe.getResult();

        if (result.getType().isAir()
                || !result.getType().isItem()) {
            return null;
        }

        List<IngredientChoice> ingredients =
                new ArrayList<>();

        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> choices =
                    shaped.getChoiceMap();

            for (String row : shaped.getShape()) {
                for (int index = 0;
                     index < row.length();
                     index++) {
                    char key =
                            row.charAt(index);

                    if (key == ' ') {
                        continue;
                    }

                    IngredientChoice choice =
                            ingredientChoice(
                                    choices.get(key)
                            );

                    if (choice == null) {
                        return null;
                    }

                    ingredients.add(choice);
                }
            }
        } else if (recipe
                instanceof ShapelessRecipe shapeless) {
            for (RecipeChoice raw
                    : shapeless.getChoiceList()) {
                IngredientChoice choice =
                        ingredientChoice(raw);

                if (choice == null) {
                    return null;
                }

                ingredients.add(choice);
            }
        } else if (recipe
                instanceof CookingRecipe<?> cooking) {
            IngredientChoice choice =
                    ingredientChoice(
                            cooking.getInputChoice()
                    );

            if (choice == null) {
                return null;
            }

            ingredients.add(choice);
        } else if (recipe
                instanceof StonecuttingRecipe stonecutting) {
            IngredientChoice choice =
                    ingredientChoice(
                            stonecutting.getInputChoice()
                    );

            if (choice == null) {
                return null;
            }

            ingredients.add(choice);
        } else if (recipe
                instanceof SmithingTransformRecipe smithing) {
            IngredientChoice template =
                    ingredientChoice(
                            smithing.getTemplate()
                    );
            IngredientChoice base =
                    ingredientChoice(
                            smithing.getBase()
                    );
            IngredientChoice addition =
                    ingredientChoice(
                            smithing.getAddition()
                    );

            if (template == null
                    || base == null
                    || addition == null) {
                return null;
            }

            ingredients.add(template);
            ingredients.add(base);
            ingredients.add(addition);
        } else {
            /*
             * Complex, merchant, trim and data-transforming recipes are not
             * suitable for automatic material-only economic derivation.
             */
            return null;
        }

        if (ingredients.isEmpty()) {
            return null;
        }

        return new RecipeSeed(
                result.getType(),
                Math.max(
                        1,
                        result.getAmount()
                ),
                List.copyOf(ingredients)
        );
    }

    private IngredientChoice ingredientChoice(
            RecipeChoice choice
    ) {
        if (choice instanceof
                RecipeChoice.MaterialChoice materials) {
            List<Material> values =
                    materials.getChoices()
                            .stream()
                            .filter(Material::isItem)
                            .filter(material ->
                                    material != Material.AIR
                            )
                            .distinct()
                            .toList();

            return values.isEmpty()
                    ? null
                    : new IngredientChoice(values);
        }

        if (choice instanceof
                RecipeChoice.ExactChoice exact) {
            List<Material> values =
                    exact.getChoices()
                            .stream()
                            .map(ItemStack::getType)
                            .filter(Material::isItem)
                            .filter(material ->
                                    material != Material.AIR
                            )
                            .distinct()
                            .toList();

            return values.isEmpty()
                    ? null
                    : new IngredientChoice(values);
        }

        /*
         * Paper 1.21.11 can expose newer ItemTypeChoice-backed/tag choices.
         * We intentionally skip an unsupported choice rather than pick one
         * representative and risk deriving a value above the cheapest valid
         * ingredient.
         */
        return null;
    }

    private CommodityBuild discoverCommodities(
            List<Material> eligible,
            List<RecipeSeed> recipes
    ) {
        Map<Integer, SimpleConversion> simple =
                new HashMap<>();

        for (int index = 0;
             index < recipes.size();
             index++) {
            SimpleConversion conversion =
                    simpleConversion(
                            recipes.get(index)
                    );

            if (conversion != null) {
                simple.put(
                        index,
                        conversion
                );
            }
        }

        Set<Integer> reversible =
                new HashSet<>();
        Map<Material, List<RatioEdge>> graph =
                new EnumMap<>(
                        Material.class
                );

        for (Map.Entry<Integer, SimpleConversion> left
                : simple.entrySet()) {
            for (Map.Entry<Integer, SimpleConversion> right
                    : simple.entrySet()) {
                if (left.getKey()
                        >= right.getKey()) {
                    continue;
                }

                SimpleConversion first =
                        left.getValue();
                SimpleConversion second =
                        right.getValue();

                if (first.input()
                        != second.output()
                        || first.output()
                        != second.input()) {
                    continue;
                }

                long leftMass =
                        safeMultiply(
                                first.inputAmount(),
                                second.inputAmount()
                        );
                long rightMass =
                        safeMultiply(
                                first.outputAmount(),
                                second.outputAmount()
                        );

                if (rightMass <= 0L
                        || leftMass != rightMass) {
                    continue;
                }

                reversible.add(
                        left.getKey()
                );
                reversible.add(
                        right.getKey()
                );

                /*
                 * first.inputAmount * units(input)
                 * = first.outputAmount * units(output)
                 *
                 * Therefore:
                 * units(output) / units(input)
                 * = inputAmount / outputAmount
                 */
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

        Map<Material, CommodityInfo> info =
                new EnumMap<>(
                        Material.class
                );
        Set<Material> visited =
                EnumSet.noneOf(
                        Material.class
                );
        int groups = 0;

        for (Material start : eligible) {
            if (visited.contains(start)
                    || !graph.containsKey(start)) {
                continue;
            }

            Map<Material, Fraction> ratios =
                    componentRatios(
                            start,
                            graph
                    );

            if (ratios.size() < 2) {
                continue;
            }

            visited.addAll(
                    ratios.keySet()
            );
            groups++;

            long commonDenominator = 1L;

            for (Fraction fraction
                    : ratios.values()) {
                commonDenominator =
                        lcm(
                                commonDenominator,
                                fraction.denominator()
                        );
            }

            Map<Material, Long> rawUnits =
                    new EnumMap<>(
                            Material.class
                    );
            long unitsGcd = 0L;

            for (Map.Entry<Material, Fraction> entry
                    : ratios.entrySet()) {
                Fraction fraction =
                        entry.getValue();
                long scale =
                        commonDenominator
                                / fraction.denominator();
                long units =
                        safeMultiply(
                                fraction.numerator(),
                                scale
                        );

                if (units <= 0L
                        || units == Long.MAX_VALUE) {
                    rawUnits.clear();
                    break;
                }

                rawUnits.put(
                        entry.getKey(),
                        units
                );
                unitsGcd =
                        unitsGcd == 0L
                                ? units
                                : gcd(
                                        unitsGcd,
                                        units
                                );
            }

            if (rawUnits.isEmpty()) {
                continue;
            }

            long divisor =
                    Math.max(
                            1L,
                            unitsGcd
                    );

            Material keyMaterial =
                    rawUnits.entrySet()
                            .stream()
                            .min(
                                    Comparator
                                            .comparingLong(
                                                    (Map.Entry<Material, Long> entry) ->
                                                            entry.getValue()
                                                                    / divisor
                                            )
                                            .thenComparing(
                                                    (Map.Entry<Material, Long> entry) ->
                                                            entry.getKey()
                                                                    .name()
                                            )
                            )
                            .map(
                                    Map.Entry::getKey
                            )
                            .orElse(start);

            String marketKey =
                    keyMaterial.name();

            for (Map.Entry<Material, Long> entry
                    : rawUnits.entrySet()) {
                info.put(
                        entry.getKey(),
                        new CommodityInfo(
                                marketKey,
                                Math.max(
                                        1L,
                                        entry.getValue()
                                                / divisor
                                )
                        )
                );
            }
        }

        return new CommodityBuild(
                Map.copyOf(info),
                Set.copyOf(reversible),
                groups
        );
    }

    private Map<Material, Fraction> componentRatios(
            Material start,
            Map<Material, List<RatioEdge>> graph
    ) {
        Map<Material, Fraction> ratios =
                new EnumMap<>(
                        Material.class
                );
        Queue<Material> queue =
                new ArrayDeque<>();

        ratios.put(
                start,
                Fraction.ONE
        );
        queue.add(start);

        while (!queue.isEmpty()) {
            Material current =
                    queue.remove();
            Fraction currentRatio =
                    ratios.get(current);

            for (RatioEdge edge
                    : graph.getOrDefault(
                            current,
                            List.of()
                    )) {
                Fraction next =
                        currentRatio.multiply(
                                edge.numerator(),
                                edge.denominator()
                        );

                Fraction existing =
                        ratios.get(
                                edge.to()
                        );

                if (existing == null) {
                    ratios.put(
                            edge.to(),
                            next
                    );
                    queue.add(
                            edge.to()
                    );
                }
            }
        }

        return ratios;
    }

    private void addRatioEdge(
            Map<Material, List<RatioEdge>> graph,
            Material from,
            Material to,
            long numerator,
            long denominator
    ) {
        graph.computeIfAbsent(
                from,
                ignored -> new ArrayList<>()
        ).add(
                new RatioEdge(
                        to,
                        numerator,
                        denominator
                )
        );
    }

    private SimpleConversion simpleConversion(
            RecipeSeed recipe
    ) {
        Material input = null;
        long inputAmount = 0L;

        for (IngredientChoice choice
                : recipe.ingredients()) {
            if (choice.materials()
                    .size() != 1) {
                return null;
            }

            Material current =
                    choice.materials()
                            .getFirst();

            if (input == null) {
                input = current;
            } else if (input != current) {
                return null;
            }

            inputAmount++;
        }

        if (input == null
                || input == recipe.output()) {
            return null;
        }

        return new SimpleConversion(
                input,
                inputAmount,
                recipe.output(),
                recipe.outputAmount()
        );
    }

    private void applyCommodityPrices(
            Map<Material, Long> prices,
            Map<Material, PriceSource> sources,
            Set<Material> explicit,
            CommodityBuild commodities
    ) {
        Map<String, List<Material>> groups =
                new LinkedHashMap<>();

        for (Map.Entry<Material, CommodityInfo> entry
                : commodities.info().entrySet()) {
            groups.computeIfAbsent(
                    entry.getValue().marketKey(),
                    ignored -> new ArrayList<>()
            ).add(entry.getKey());
        }

        for (List<Material> members : groups.values()) {
            BigDecimal safestPerUnit = null;

            /*
             * A reversible commodity group must be anchored by a trusted
             * economic value. Curated values are trusted immediately;
             * recipe-derived values become trusted after their ingredient
             * path was itself anchored. A group made only from one-cent
             * placeholders is deliberately left review-only.
             */
            for (Material material : members) {
                PriceSource source =
                        sources.getOrDefault(
                                material,
                                PriceSource.GENERATED_CATEGORY
                        );

                if (source != PriceSource.CURATED
                        && source != PriceSource.GENERATED_RECIPE
                        && source != PriceSource.GENERATED_COMMODITY) {
                    continue;
                }

                long price =
                        prices.getOrDefault(
                                material,
                                0L
                        );
                CommodityInfo info =
                        commodities.info().get(material);

                if (info == null
                        || price <= 0L
                        || info.marketUnits() <= 0L) {
                    continue;
                }

                BigDecimal perUnit =
                        BigDecimal.valueOf(price)
                                .divide(
                                        BigDecimal.valueOf(
                                                info.marketUnits()
                                        ),
                                        12,
                                        RoundingMode.DOWN
                                );

                if (safestPerUnit == null
                        || perUnit.compareTo(
                        safestPerUnit
                ) < 0) {
                    safestPerUnit = perUnit;
                }
            }

            if (safestPerUnit == null
                    || safestPerUnit.signum() <= 0) {
                continue;
            }

            for (Material material : members) {
                if (explicit.contains(material)
                        || RUNTIME_VARIANT_MATERIALS.contains(material)) {
                    continue;
                }

                CommodityInfo info =
                        commodities.info().get(material);

                if (info == null
                        || info.marketUnits() <= 0L) {
                    continue;
                }

                long derived =
                        safestPerUnit
                                .multiply(
                                        BigDecimal.valueOf(
                                                info.marketUnits()
                                        )
                                )
                                .setScale(
                                        0,
                                        RoundingMode.DOWN
                                )
                                .max(BigDecimal.ONE)
                                .longValue();

                long current =
                        prices.getOrDefault(
                                material,
                                Long.MAX_VALUE
                        );
                PriceSource currentSource =
                        sources.getOrDefault(
                                material,
                                PriceSource.GENERATED_CATEGORY
                        );

                if (currentSource
                        == PriceSource.GENERATED_CATEGORY
                        || derived <= current) {
                    prices.put(
                            material,
                            derived
                    );
                    sources.put(
                            material,
                            PriceSource.GENERATED_COMMODITY
                    );
                }
            }
        }
    }

    private Set<Material> resolveRecipeFloorSafeMaterials(
            SellService sellService,
            FileConfiguration config,
            List<Material> eligible,
            Map<Material, Long> prices,
            Map<Material, PriceSource> sources,
            Set<Material> explicit,
            List<RecipeSeed> recipes,
            Set<Integer> reversibleRecipes,
            Set<Material> allRecipeOutputs,
            Set<Material> unsupportedRecipeOutputs,
            Set<Material> dynamicArbitrageUnsafe
    ) {
        Map<Material, List<RecipeSeed>> recipesByOutput =
                new EnumMap<>(
                        Material.class
                );
        Set<Material> reversibleOutputs =
                EnumSet.noneOf(
                        Material.class
                );

        for (int index = 0;
             index < recipes.size();
             index++) {
            RecipeSeed recipe =
                    recipes.get(index);

            recipesByOutput
                    .computeIfAbsent(
                            recipe.output(),
                            ignored ->
                                    new ArrayList<>()
                    )
                    .add(recipe);

            if (reversibleRecipes.contains(
                    index
            )) {
                reversibleOutputs.add(
                        recipe.output()
                );
            }
        }

        Set<Material> baseApproved =
                EnumSet.noneOf(
                        Material.class
                );
        Set<Material> candidates =
                EnumSet.noneOf(
                        Material.class
                );

        for (Material material : eligible) {
            double buyback =
                    configuredBuybackMultiplier(
                            sellService,
                            config,
                            material
                    );

            if (buyback <= 0.0D) {
                continue;
            }

            PriceSource source =
                    sources.getOrDefault(
                            material,
                            PriceSource.GENERATED_CATEGORY
                    );
            boolean variant =
                    RUNTIME_VARIANT_MATERIALS.contains(
                            material
                    );
            boolean currentTrusted =
                    explicit.contains(material)
                            && sellService
                            .isServerSellableMaterial(
                                    material
                            )
                            && !dynamicArbitrageUnsafe.contains(
                            material
                    );
            boolean generatedTrusted =
                    (source
                            == PriceSource.GENERATED_RECIPE
                            || source
                            == PriceSource.GENERATED_COMMODITY)
                            && !dynamicArbitrageUnsafe.contains(
                            material
                    );
            boolean naturalFloor =
                    source
                            == PriceSource.GENERATED_CATEGORY
                            && !allRecipeOutputs.contains(
                            material
                    );

            if (variant
                    || currentTrusted
                    || generatedTrusted
                    || naturalFloor) {
                baseApproved.add(
                        material
                );
            }

            if (source
                    != PriceSource.GENERATED_CATEGORY
                    || variant
                    || !allRecipeOutputs.contains(
                    material
            )
                    || unsupportedRecipeOutputs.contains(
                    material
            )
                    || reversibleOutputs.contains(
                    material
            )
                    || !recipesByOutput.containsKey(
                    material
            )) {
                continue;
            }

            candidates.add(
                    material
            );
        }

        if (candidates.isEmpty()) {
            return Set.of();
        }

        Set<Material> remaining =
                EnumSet.copyOf(
                        candidates
                );
        Set<Material> approved =
                EnumSet.noneOf(
                        Material.class
                );
        approved.addAll(
                baseApproved
        );
        approved.addAll(
                remaining
        );

        /*
         * Start with every analyzable one-cent recipe output enabled, then
         * repeatedly remove any output that could produce more server-buyback
         * value than the currently approved ingredients it consumes.
         *
         * This is intentionally removal-only. A row that fails once stays in
         * review for this catalog revision instead of becoming safe only
         * because another questionable row was removed later.
         */
        for (int pass = 0;
             pass < DERIVATION_PASSES;
             pass++) {
            Set<Material> remove =
                    EnumSet.noneOf(
                            Material.class
                    );

            for (Material output : remaining) {
                if (!recipeFloorSafe(
                        sellService,
                        config,
                        output,
                        recipesByOutput.getOrDefault(
                                output,
                                List.of()
                        ),
                        prices,
                        sources,
                        approved,
                        candidates
                )) {
                    remove.add(
                            output
                    );
                }
            }

            if (remove.isEmpty()) {
                break;
            }

            remaining.removeAll(
                    remove
            );
            approved.removeAll(
                    remove
            );
        }

        return Set.copyOf(
                remaining
        );
    }

    private boolean recipeFloorSafe(
            SellService sellService,
            FileConfiguration config,
            Material output,
            List<RecipeSeed> outputRecipes,
            Map<Material, Long> prices,
            Map<Material, PriceSource> sources,
            Set<Material> approved,
            Set<Material> fixedRecipeFloors
    ) {
        if (outputRecipes.isEmpty()) {
            return false;
        }

        long outputBase =
                prices.getOrDefault(
                        output,
                        UNTRUSTED_FLOOR_CENTS
                );
        double outputBuyback =
                configuredBuybackMultiplier(
                        sellService,
                        config,
                        output
                );

        if (outputBase <= 0L
                || outputBuyback <= 0.0D) {
            return false;
        }

        for (RecipeSeed recipe : outputRecipes) {
            BigDecimal outputValue =
                    BigDecimal.valueOf(
                            outputBase
                    )
                            .multiply(
                                    BigDecimal.valueOf(
                                            Math.max(
                                                    1,
                                                    recipe.outputAmount()
                                            )
                                    )
                            )
                            .multiply(
                                    BigDecimal.valueOf(
                                            outputBuyback
                                    )
                            );

            BigDecimal inputBudget =
                    BigDecimal.ZERO;

            for (IngredientChoice choice
                    : recipe.ingredients()) {
                BigDecimal cheapest =
                        null;

                for (Material ingredient
                        : choice.materials()) {
                    if (!approved.contains(
                            ingredient
                    )) {
                        continue;
                    }

                    BigDecimal value =
                            minimumApprovedSellValue(
                                    sellService,
                                    config,
                                    ingredient,
                                    prices,
                                    sources,
                                    fixedRecipeFloors
                            );

                    if (value.signum() <= 0) {
                        continue;
                    }

                    if (cheapest == null
                            || value.compareTo(
                            cheapest
                    ) < 0) {
                        cheapest = value;
                    }
                }

                if (cheapest != null) {
                    inputBudget =
                            inputBudget.add(
                                    cheapest
                            );
                }
            }

            /*
             * No currently sellable input means there is no server-buyback
             * conversion loop to exploit. Selling the crafted output is simply
             * monetizing resources that otherwise have no server cash-out.
             */
            if (inputBudget.signum() <= 0) {
                continue;
            }

            BigDecimal safeBudget =
                    inputBudget.multiply(
                            BigDecimal.valueOf(
                                    RECIPE_HAIRCUT
                            )
                    );

            if (outputValue.compareTo(
                    safeBudget
            ) > 0) {
                return false;
            }
        }

        return true;
    }

    private BigDecimal minimumApprovedSellValue(
            SellService sellService,
            FileConfiguration config,
            Material material,
            Map<Material, Long> prices,
            Map<Material, PriceSource> sources,
            Set<Material> fixedRecipeFloors
    ) {
        long base =
                prices.getOrDefault(
                        material,
                        0L
                );

        if (base <= 0L) {
            return BigDecimal.ZERO;
        }

        double buyback =
                configuredBuybackMultiplier(
                        sellService,
                        config,
                        material
                );

        if (buyback <= 0.0D) {
            return BigDecimal.ZERO;
        }

        boolean fixed =
                fixedRecipeFloors.contains(
                        material
                )
                        || RUNTIME_VARIANT_MATERIALS.contains(
                        material
                );
        double marketMultiplier =
                fixed
                        ? 1.0D
                        : predictedMarketEnabled(
                        sellService,
                        config,
                        material,
                        sources.getOrDefault(
                                material,
                                PriceSource.GENERATED_CATEGORY
                        )
                )
                        ? configuredMinimumMultiplier(
                        config,
                        material
                )
                        : 1.0D;

        return BigDecimal.valueOf(
                base
        )
                .multiply(
                        BigDecimal.valueOf(
                                marketMultiplier
                        )
                )
                .multiply(
                        BigDecimal.valueOf(
                                buyback
                        )
                );
    }

    private boolean predictedMarketEnabled(
            SellService sellService,
            FileConfiguration config,
            Material material,
            PriceSource source
    ) {
        if (RUNTIME_VARIANT_MATERIALS.contains(
                material
        )
                || source == PriceSource.VARIANT_SAFE) {
            return false;
        }

        String category =
                normalizeCategory(
                        sellService.category(
                                material
                        )
                );
        boolean categoryEnabled =
                config.getBoolean(
                        "market.categories."
                                + category
                                + ".enabled",
                        defaultMarketEnabled(
                                category
                        )
                );

        return config.getBoolean(
                "prices."
                        + material.name()
                        + ".market-enabled",
                categoryEnabled
        );
    }

    private Set<Material> applyDynamicArbitrageCeilings(
            SellService sellService,
            FileConfiguration config,
            Map<Material, Long> prices,
            Map<Material, PriceSource> sources,
            Set<Material> explicit,
            List<RecipeSeed> recipes,
            Set<Integer> reversibleRecipes
    ) {
        for (int pass = 0;
             pass < DERIVATION_PASSES;
             pass++) {
            boolean changed = false;

            for (int index = 0;
                 index < recipes.size();
                 index++) {
                if (reversibleRecipes.contains(index)) {
                    continue;
                }

                RecipeSeed recipe =
                        recipes.get(index);
                Material output =
                        recipe.output();

                if (!prices.containsKey(output)
                        || explicit.contains(output)
                        || RUNTIME_VARIANT_MATERIALS.contains(output)) {
                    continue;
                }

                long ceiling =
                        dynamicSafeBaseCeiling(
                                sellService,
                                config,
                                recipe,
                                prices
                        );

                if (ceiling <= 0L
                        || ceiling == Long.MAX_VALUE) {
                    continue;
                }

                long current =
                        prices.getOrDefault(
                                output,
                                1L
                        );
                long next =
                        Math.clamp(
                                current,
                                1L,
                                ceiling
                        );

                if (next < current) {
                    prices.put(
                            output,
                            next
                    );
                    sources.put(
                            output,
                            PriceSource.GENERATED_RECIPE
                    );
                    changed = true;
                }
            }

            if (!changed) {
                break;
            }
        }

        Set<Material> unsafe =
                EnumSet.noneOf(
                        Material.class
                );

        for (int index = 0;
             index < recipes.size();
             index++) {
            if (reversibleRecipes.contains(index)) {
                continue;
            }

            RecipeSeed recipe =
                    recipes.get(index);
            Material output =
                    recipe.output();

            if (!prices.containsKey(output)
                    || RUNTIME_VARIANT_MATERIALS.contains(output)) {
                continue;
            }

            long ceiling =
                    dynamicSafeBaseCeiling(
                            sellService,
                            config,
                            recipe,
                            prices
                    );

            if (ceiling <= 0L
                    || prices.getOrDefault(
                    output,
                    1L
            ) > ceiling) {
                unsafe.add(output);
            }
        }

        return Set.copyOf(unsafe);
    }

    private long dynamicSafeBaseCeiling(
            SellService sellService,
            FileConfiguration config,
            RecipeSeed recipe,
            Map<Material, Long> prices
    ) {
        long ingredientBudget = 0L;

        for (IngredientChoice choice
                : recipe.ingredients()) {
            long cheapest = Long.MAX_VALUE;

            for (Material material
                    : choice.materials()) {
                long base =
                        prices.getOrDefault(
                                material,
                                0L
                        );

                if (base <= 0L) {
                    continue;
                }

                double minimum =
                        configuredMinimumMultiplier(
                                config,
                                material
                        );
                long economicFloor =
                        multiplyDown(
                                base,
                                minimum
                        );

                if (economicFloor > 0L) {
                    cheapest =
                            Math.min(
                                    cheapest,
                                    economicFloor
                            );
                }
            }

            if (cheapest == Long.MAX_VALUE) {
                return 0L;
            }

            ingredientBudget =
                    safeAdd(
                            ingredientBudget,
                            cheapest
                    );

            if (ingredientBudget
                    == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
        }

        Material output =
                recipe.output();
        double buyback =
                configuredBuybackMultiplier(
                        sellService,
                        config,
                        output
                );

        if (buyback <= 0.0D) {
            return Long.MAX_VALUE;
        }

        double maximum =
                configuredMaximumMultiplier(
                        config,
                        output
                );

        try {
            BigDecimal budget =
                    BigDecimal.valueOf(
                            ingredientBudget
                    )
                            .multiply(
                                    BigDecimal.valueOf(
                                            RECIPE_HAIRCUT
                                    )
                            );
            BigDecimal divisor =
                    BigDecimal.valueOf(
                            Math.max(
                                    1,
                                    recipe.outputAmount()
                            )
                    )
                            .multiply(
                                    BigDecimal.valueOf(
                                            buyback
                                    )
                            )
                            .multiply(
                                    BigDecimal.valueOf(
                                            maximum
                                    )
                            );

            if (divisor.signum() <= 0) {
                return 0L;
            }

            return budget.divide(
                            divisor,
                            0,
                            RoundingMode.DOWN
                    )
                    .max(
                            BigDecimal.ZERO
                    )
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private double configuredMinimumMultiplier(
            FileConfiguration config,
            Material material
    ) {
        String path =
                "prices."
                        + material.name()
                        + ".minimum-multiplier";

        return clamp(
                config.getDouble(
                        path,
                        config.getDouble(
                                "market.minimum-multiplier",
                                0.35D
                        )
                ),
                0.01D,
                100.0D
        );
    }

    private double configuredMaximumMultiplier(
            FileConfiguration config,
            Material material
    ) {
        double minimum =
                configuredMinimumMultiplier(
                        config,
                        material
                );
        String path =
                "prices."
                        + material.name()
                        + ".maximum-multiplier";

        return clamp(
                config.getDouble(
                        path,
                        config.getDouble(
                                "market.maximum-multiplier",
                                1.75D
                        )
                ),
                minimum,
                100.0D
        );
    }

    private double configuredBuybackMultiplier(
            SellService sellService,
            FileConfiguration config,
            Material material
    ) {
        String category =
                normalizeCategory(
                        sellService.category(
                                material
                        )
                );
        double categoryBuyback =
                clamp(
                        config.getDouble(
                                "valuation.category-buyback."
                                        + category
                                        + ".base",
                                1.0D
                        ),
                        0.0D,
                        1.0D
                );

        return clamp(
                config.getDouble(
                        "prices."
                                + material.name()
                                + ".buyback-multiplier",
                        categoryBuyback
                ),
                0.0D,
                1.0D
        );
    }

    private long multiplyDown(
            long value,
            double multiplier
    ) {
        if (value <= 0L
                || !Double.isFinite(multiplier)
                || multiplier <= 0.0D) {
            return 0L;
        }

        try {
            return BigDecimal.valueOf(value)
                    .multiply(
                            BigDecimal.valueOf(
                                    multiplier
                            )
                    )
                    .setScale(
                            0,
                            RoundingMode.DOWN
                    )
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long ingredientCost(
            RecipeSeed recipe,
            Map<Material, Long> prices,
            Map<Material, PriceSource> sources
    ) {
        long total = 0L;

        for (IngredientChoice choice
                : recipe.ingredients()) {
            long cheapest = Long.MAX_VALUE;

            for (Material material
                    : choice.materials()) {
                PriceSource source =
                        sources.getOrDefault(
                                material,
                                PriceSource.GENERATED_CATEGORY
                        );

                /*
                 * Do not derive a trusted recipe value from another unknown
                 * one-cent placeholder. This makes trust propagate outward
                 * from curated/verified anchors instead of manufacturing a
                 * giant network of meaningless one-cent "safe" recipes.
                 */
                if (source != PriceSource.CURATED
                        && source != PriceSource.GENERATED_RECIPE
                        && source != PriceSource.GENERATED_COMMODITY) {
                    continue;
                }

                long price =
                        prices.getOrDefault(
                                material,
                                0L
                        );

                if (price > 0L) {
                    cheapest =
                            Math.min(
                                    cheapest,
                                    price
                            );
                }
            }

            if (cheapest == Long.MAX_VALUE) {
                return 0L;
            }

            total =
                    safeAdd(
                            total,
                            cheapest
                    );

            if (total == Long.MAX_VALUE) {
                return total;
            }
        }

        return total;
    }

    private long recipeUnitValue(
            long ingredientTotal,
            int outputAmount
    ) {
        try {
            return BigDecimal
                    .valueOf(
                            ingredientTotal
                    )
                    .multiply(
                            BigDecimal.valueOf(
                                    RECIPE_HAIRCUT
                            )
                    )
                    .divide(
                            BigDecimal.valueOf(
                                    Math.max(
                                            1,
                                            outputAmount
                                    )
                            ),
                            0,
                            RoundingMode.DOWN
                    )
                    .max(
                            BigDecimal.ONE
                    )
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private SellCatalogSnapshot loadRuntimeSnapshot(
            Connection connection,
            String table,
            String metaTable
    ) throws Exception {
        int expectedRows;

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT catalog_revision,
                                    expected_rows,
                                    valid_rows,
                                    missing_rows,
                                    invalid_rows,
                                    status
                               FROM %s
                              WHERE singleton_id = 1
                             """.formatted(metaTable));
             ResultSet result =
                     statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException(
                        "Sell catalog meta row is missing"
                );
            }

            int revision =
                    result.getInt(
                            "catalog_revision"
                    );
            expectedRows =
                    result.getInt(
                            "expected_rows"
                    );
            int validRows =
                    result.getInt(
                            "valid_rows"
                    );
            int missingRows =
                    result.getInt(
                            "missing_rows"
                    );
            int invalidRows =
                    result.getInt(
                            "invalid_rows"
                    );
            String status =
                    result.getString(
                            "status"
                    );

            if (revision != CATALOG_REVISION
                    || !"READY".equalsIgnoreCase(status)
                    || expectedRows != seeds.size()
                    || validRows != expectedRows
                    || missingRows != 0
                    || invalidRows != 0) {
                throw new IllegalStateException(
                        "Sell catalog meta is not activation-ready"
                );
            }
        }

        Map<Material, SellCatalogEntry> entries =
                new EnumMap<>(Material.class);
        Map<String, CatalogSeed> expectedSeeds =
                new HashMap<>();

        for (CatalogSeed seed : seeds) {
            expectedSeeds.put(
                    seed.material()
                            .toUpperCase(Locale.ROOT),
                    seed
            );
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT material,
                                    category,
                                    base_price_cents,
                                    server_sell_enabled,
                                    market_enabled,
                                    market_key,
                                    market_units,
                                    target_units_per_day,
                                    minimum_multiplier,
                                    maximum_multiplier,
                                    buyback_multiplier,
                                    enchant_buyback_multiplier,
                                    price_source,
                                    auto_sell_approved,
                                    activation_state,
                                    operator_locked,
                                    catalog_revision
                               FROM %s
                             """.formatted(table));
             ResultSet result =
                     statement.executeQuery()) {
            while (result.next()) {
                String rawMaterial =
                        result.getString(
                                "material"
                        );

                if (rawMaterial == null) {
                    continue;
                }

                CatalogSeed expectedSeed =
                        expectedSeeds.get(
                                rawMaterial.toUpperCase(
                                        Locale.ROOT
                                )
                        );

                if (expectedSeed == null) {
                    continue;
                }

                Material material =
                        Material.matchMaterial(
                                rawMaterial
                        );

                if (material == null) {
                    continue;
                }

                CatalogRow validationRow =
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
                                result.getDouble(
                                        "enchant_buyback_multiplier"
                                ),
                                result.getString("price_source"),
                                result.getBoolean("auto_sell_approved"),
                                result.getString("activation_state"),
                                result.getBoolean("operator_locked"),
                                result.getInt("catalog_revision")
                        );

                if (invalidRow(
                        validationRow,
                        expectedSeed
                )) {
                    throw new IllegalStateException(
                            "Sell runtime snapshot contains invalid row: "
                                    + material
                    );
                }

                entries.put(
                        material,
                        new SellCatalogEntry(
                                material,
                                validationRow.basePriceCents(),
                                validationRow.category(),
                                validationRow.serverSellEnabled(),
                                validationRow.marketEnabled(),
                                validationRow.marketKey(),
                                validationRow.marketUnits(),
                                validationRow.targetUnitsPerDay(),
                                validationRow.minimumMultiplier(),
                                validationRow.maximumMultiplier(),
                                validationRow.buybackMultiplier(),
                                validationRow.enchantBuybackMultiplier(),
                                validationRow.priceSource(),
                                validationRow.autoSellApproved(),
                                validationRow.activationState(),
                                validationRow.operatorLocked(),
                                validationRow.catalogRevision()
                        )
                );
            }
        }

        if (entries.size() != expectedRows) {
            throw new IllegalStateException(
                    "Sell runtime snapshot is incomplete: "
                            + entries.size()
                            + "/"
                            + expectedRows
            );
        }

        return new SellCatalogSnapshot(
                CATALOG_REVISION,
                expectedRows,
                System.currentTimeMillis(),
                Map.copyOf(entries)
        );
    }

    private void initializeMeta(
            Connection connection,
            String metaTable
    ) throws Exception {
        try (Statement statement =
                     connection.createStatement()) {
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

    private DatabaseAudit audit(
            Connection connection,
            String table
    ) throws Exception {
        Map<String, CatalogRow> rows =
                new HashMap<>();

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT material,
                                    category,
                                    base_price_cents,
                                    server_sell_enabled,
                                    market_enabled,
                                    market_key,
                                    market_units,
                                    target_units_per_day,
                                    minimum_multiplier,
                                    maximum_multiplier,
                                    buyback_multiplier,
                                    enchant_buyback_multiplier,
                                    price_source,
                                    auto_sell_approved,
                                    activation_state,
                                    operator_locked,
                                    catalog_revision
                               FROM %s
                             """.formatted(table));
             ResultSet result =
                     statement.executeQuery()) {
            while (result.next()) {
                String material =
                        result.getString("material");

                if (material == null
                        || material.isBlank()) {
                    continue;
                }

                rows.put(
                        material.toUpperCase(
                                Locale.ROOT
                        ),
                        new CatalogRow(
                                result.getString(
                                        "category"
                                ),
                                result.getLong(
                                        "base_price_cents"
                                ),
                                result.getBoolean(
                                        "server_sell_enabled"
                                ),
                                result.getBoolean(
                                        "market_enabled"
                                ),
                                result.getString(
                                        "market_key"
                                ),
                                result.getLong(
                                        "market_units"
                                ),
                                result.getLong(
                                        "target_units_per_day"
                                ),
                                result.getDouble(
                                        "minimum_multiplier"
                                ),
                                result.getDouble(
                                        "maximum_multiplier"
                                ),
                                result.getDouble(
                                        "buyback_multiplier"
                                ),
                                result.getDouble(
                                        "enchant_buyback_multiplier"
                                ),
                                result.getString(
                                        "price_source"
                                ),
                                result.getBoolean(
                                        "auto_sell_approved"
                                ),
                                result.getString(
                                        "activation_state"
                                ),
                                result.getBoolean(
                                        "operator_locked"
                                ),
                                result.getInt(
                                        "catalog_revision"
                                )
                        )
                );
            }
        }

        int missing = 0;
        int invalid = 0;
        int valid = 0;
        int sellEnabled = 0;
        int autoApproved = 0;
        int review = 0;

        for (CatalogSeed seed : seeds) {
            CatalogRow row =
                    rows.get(
                            seed.material()
                    );

            if (row == null) {
                missing++;
                continue;
            }

            if (invalidRow(
                    row,
                    seed
            )) {
                invalid++;
                continue;
            }

            valid++;

            if (row.serverSellEnabled()) {
                sellEnabled++;
            } else {
                review++;
            }

            if (row.autoSellApproved()) {
                autoApproved++;
            }
        }

        return new DatabaseAudit(
                missing == 0
                        && invalid == 0
                        && valid == seeds.size(),
                seeds.size(),
                valid,
                sellEnabled,
                autoApproved,
                review,
                missing,
                invalid
        );
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
                || !Double.isFinite(
                row.minimumMultiplier()
        )
                || row.minimumMultiplier()
                <= 0.0D
                || !Double.isFinite(
                row.maximumMultiplier()
        )
                || row.maximumMultiplier()
                < row.minimumMultiplier()
                || outsideUnitInterval(
                row.buybackMultiplier()
        )
                || outsideUnitInterval(
                row.enchantBuybackMultiplier()
        )
                || row.priceSource() == null
                || row.priceSource().isBlank()
                || row.activationState() == null
                || row.activationState().isBlank()) {
            return true;
        }

        if (!row.operatorLocked()
                && row.catalogRevision()
                < CATALOG_REVISION) {
            return true;
        }

        /*
         * auto_sell_approved is migration proof that a generated value passed
         * our mechanical safety rules. It may never be true while the actual
         * server-sell switch is false.
         */
        if (row.autoSellApproved()
                && !row.serverSellEnabled()) {
            return true;
        }

        if (row.serverSellEnabled()
                && row.buybackMultiplier() <= 0.0D) {
            return true;
        }

        if (row.serverSellEnabled()
                && minimumServerUnitCents(
                row
        ) <= 0L) {
            return true;
        }

        if (row.serverSellEnabled()
                && safetyFloorActivationState(
                row.activationState()
        )
                && !validSafetyFloorInvariant(
                row
        )) {
            return true;
        }

        /*
         * Migration-owned rows have one mechanical authority bit:
         * auto_sell_approved and server_sell_enabled must agree. An
         * operator-locked row may intentionally override this relationship.
         */
        if (!row.operatorLocked()
                && row.serverSellEnabled()
                != row.autoSellApproved()) {
            return true;
        }

        /*
         * An operator lock may lower or disable an automatically generated
         * value, but it may not silently raise an enabled payout beyond the
         * mechanically audited revision-8 ceiling. That would bypass the
         * crafting-arbitrage guarantee while still allowing the catalog to
         * report READY.
         */
        if (row.operatorLocked()
                && row.serverSellEnabled()
                && (!seed.autoSellApproved()
                || row.basePriceCents()
                > seed.basePriceCents()
                || row.maximumMultiplier()
                > seed.maximumMultiplier()
                || row.buybackMultiplier()
                > seed.buybackMultiplier()
                || (row.marketEnabled()
                && !seed.marketEnabled()))) {
            return true;
        }

        /*
         * Market movement cannot be active for a row that is not accepted by
         * the server. Otherwise /worth could show a demand-driven price that
         * no sale can ever contribute supply toward.
         */
        return row.marketEnabled()
                && !row.serverSellEnabled();
    }

    private boolean validSafetyFloorInvariant(
            CatalogRow row
    ) {
        return row.basePriceCents() == UNTRUSTED_FLOOR_CENTS
                && !row.marketEnabled()
                && Double.compare(
                row.minimumMultiplier(),
                1.0D
        ) == 0
                && Double.compare(
                row.maximumMultiplier(),
                1.0D
        ) == 0
                && Double.compare(
                row.buybackMultiplier(),
                1.0D
        ) == 0
                && Double.compare(
                row.enchantBuybackMultiplier(),
                0.0D
        ) == 0;
    }

    private long minimumServerUnitCents(
            long baseCents,
            boolean marketEnabled,
            double minimumMultiplier,
            double buybackMultiplier
    ) {
        double marketMultiplier =
                marketEnabled
                        ? minimumMultiplier
                        : 1.0D;

        try {
            return BigDecimal
                    .valueOf(
                            baseCents
                    )
                    .multiply(
                            BigDecimal.valueOf(
                                    marketMultiplier
                            )
                    )
                    .multiply(
                            BigDecimal.valueOf(
                                    buybackMultiplier
                            )
                    )
                    .setScale(
                            0,
                            RoundingMode.HALF_UP
                    )
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return 0L;
        }
    }

    private long minimumServerUnitCents(
            CatalogRow row
    ) {
        return minimumServerUnitCents(
                row.basePriceCents(),
                row.marketEnabled(),
                row.minimumMultiplier(),
                row.buybackMultiplier()
        );
    }

    private boolean outsideUnitInterval(
            double value
    ) {
        return !Double.isFinite(value)
                || value < 0.0D
                || value > 1.0D;
    }

    private void writeMeta(
            Connection connection,
            String metaTable,
            DatabaseAudit audit
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 singleton_id,
                                 catalog_revision,
                                 expected_rows,
                                 valid_rows,
                                 sell_enabled_rows,
                                 auto_approved_rows,
                                 review_rows,
                                 missing_rows,
                                 invalid_rows,
                                 status,
                                 updated_at
                             ) VALUES (
                                 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                             )
                             ON DUPLICATE KEY UPDATE
                                 catalog_revision =
                                     VALUES(catalog_revision),
                                 expected_rows =
                                     VALUES(expected_rows),
                                 valid_rows =
                                     VALUES(valid_rows),
                                 sell_enabled_rows =
                                     VALUES(sell_enabled_rows),
                                 auto_approved_rows =
                                     VALUES(auto_approved_rows),
                                 review_rows =
                                     VALUES(review_rows),
                                 missing_rows =
                                     VALUES(missing_rows),
                                 invalid_rows =
                                     VALUES(invalid_rows),
                                 status =
                                     VALUES(status),
                                 updated_at =
                                     VALUES(updated_at)
                             """.formatted(metaTable))) {
            statement.setInt(
                    1,
                    CATALOG_REVISION
            );
            statement.setInt(
                    2,
                    audit.expectedRows()
            );
            statement.setInt(
                    3,
                    audit.validRows()
            );
            statement.setInt(
                    4,
                    audit.sellEnabledRows()
            );
            statement.setInt(
                    5,
                    audit.autoApprovedRows()
            );
            statement.setInt(
                    6,
                    audit.reviewRows()
            );
            statement.setInt(
                    7,
                    audit.missingRows()
            );
            statement.setInt(
                    8,
                    audit.invalidRows()
            );
            statement.setString(
                    9,
                    audit.ready()
                            ? "READY"
                            : "INVALID"
            );
            statement.setLong(
                    10,
                    System.currentTimeMillis()
            );
            statement.executeUpdate();
        }
    }

    private boolean safetyFloorActivationState(
            String activationState
    ) {
        return "READY_FLOOR".equals(
                activationState
        )
                || "READY_FLOOR_RECIPE".equals(
                activationState
        )
                || "READY_VARIANT".equals(
                activationState
        );
    }

    private String activationState(
            PriceSource source,
            boolean currentTrustedSell,
            boolean autoSellApproved,
            boolean variantRequired,
            boolean categoryFloorSafe,
            boolean recipeFloorSafe,
            boolean recipeArbitrageUnsafe
    ) {
        if (variantRequired) {
            return autoSellApproved
                    ? "READY_VARIANT"
                    : "REVIEW_VARIANT";
        }

        if (recipeArbitrageUnsafe) {
            return "REVIEW_ARBITRAGE";
        }

        if (currentTrustedSell) {
            return "LIVE_CURATED";
        }

        if (autoSellApproved
                && source
                == PriceSource.GENERATED_COMMODITY) {
            return "READY_COMMODITY";
        }

        if (autoSellApproved
                && source
                == PriceSource.GENERATED_RECIPE) {
            return "READY_RECIPE";
        }

        if (autoSellApproved
                && recipeFloorSafe) {
            return "READY_FLOOR_RECIPE";
        }

        if (autoSellApproved
                && categoryFloorSafe) {
            return "READY_FLOOR";
        }

        if (source == PriceSource.CURATED) {
            return "REVIEW_CURATED";
        }

        return "REVIEW_CATEGORY";
    }

    private void initialize(
            Connection connection,
            String table
    ) throws Exception {
        try (Statement statement =
                     connection.createStatement()) {
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
                        activation_state VARCHAR(32) NOT NULL DEFAULT 'REVIEW',
                        operator_locked TINYINT(1) NOT NULL DEFAULT 0,
                        catalog_revision INT NOT NULL DEFAULT 1,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        INDEX idx_sell_items_category (category),
                        INDEX idx_sell_items_market_key (market_key),
                        INDEX idx_sell_items_sell_enabled (
                            server_sell_enabled
                        ),
                        INDEX idx_sell_items_market_enabled (
                            market_enabled
                        )
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(table));
        }
    }

    /**
     * Phase 1A installations already have the table without the two v1.0.38
     * migration columns. Database metadata keeps this compatible with both
     * MySQL and MariaDB without relying on ADD COLUMN IF NOT EXISTS syntax.
     */
    private void migrateColumns(
            Connection connection,
            String table
    ) throws Exception {
        ensureColumn(
                connection,
                table,
                "operator_locked",
                "TINYINT(1) NOT NULL DEFAULT 0"
        );
        ensureColumn(
                connection,
                table,
                "catalog_revision",
                "INT NOT NULL DEFAULT 1"
        );
        ensureColumn(
                connection,
                table,
                "auto_sell_approved",
                "TINYINT(1) NOT NULL DEFAULT 0"
        );
        ensureColumn(
                connection,
                table,
                "activation_state",
                "VARCHAR(32) NOT NULL DEFAULT 'REVIEW'"
        );

        /*
         * Phase 1A used VARCHAR(24). All v1.0.38 source labels currently fit
         * that, but widening prevents future migration labels from requiring
         * another compatibility step.
         */
        try (Statement statement =
                     connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE "
                            + table
                            + " MODIFY COLUMN "
                            + "price_source "
                            + "VARCHAR(32) NOT NULL"
            );
        }
    }

    private void ensureColumn(
            Connection connection,
            String table,
            String column,
            String definition
    ) throws Exception {
        if (columnExists(
                connection,
                table,
                column
        )) {
            return;
        }

        try (Statement statement =
                     connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE "
                            + table
                            + " ADD COLUMN "
                            + column
                            + " "
                            + definition
            );
        }
    }

    private boolean columnExists(
            Connection connection,
            String table,
            String column
    ) throws Exception {
        DatabaseMetaData metadata =
                connection.getMetaData();

        try (ResultSet result =
                     metadata.getColumns(
                             connection.getCatalog(),
                             null,
                             table,
                             column
                     )) {
            if (result.next()) {
                return true;
            }
        }

        /*
         * Some MariaDB configurations normalize metadata names differently.
         */
        try (ResultSet result =
                     metadata.getColumns(
                             connection.getCatalog(),
                             null,
                             table.toUpperCase(
                                     Locale.ROOT
                             ),
                             column.toUpperCase(
                                     Locale.ROOT
                             )
                     )) {
            return result.next();
        }
    }

    private void seed(
            Connection connection,
            String table
    ) throws Exception {
        if (seeds.isEmpty()) {
            return;
        }

        long now =
                System.currentTimeMillis();

        /*
         * operator_locked=1 is an explicit administrative override. Migration
         * revisions never rewrite those rows.
         *
         * Unlocked rows are migration-owned and may safely receive improved
         * commodity keys / generated candidates on future plugin revisions.
         */
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 material,
                                 category,
                                 base_price_cents,
                                 server_sell_enabled,
                                 market_enabled,
                                 market_key,
                                 market_units,
                                 target_units_per_day,
                                 minimum_multiplier,
                                 maximum_multiplier,
                                 buyback_multiplier,
                                 enchant_buyback_multiplier,
                                 price_source,
                                 auto_sell_approved,
                                 activation_state,
                                 operator_locked,
                                 catalog_revision,
                                 created_at,
                                 updated_at
                             ) VALUES (
                                 ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                             )
                             ON DUPLICATE KEY UPDATE
                                 category =
                                     IF(operator_locked = 0,
                                        VALUES(category),
                                        category),
                                 base_price_cents =
                                     IF(operator_locked = 0,
                                        VALUES(base_price_cents),
                                        base_price_cents),
                                 server_sell_enabled =
                                     IF(operator_locked = 0,
                                        VALUES(server_sell_enabled),
                                        server_sell_enabled),
                                 market_enabled =
                                     IF(operator_locked = 0,
                                        VALUES(market_enabled),
                                        market_enabled),
                                 market_key =
                                     IF(operator_locked = 0,
                                        VALUES(market_key),
                                        market_key),
                                 market_units =
                                     IF(operator_locked = 0,
                                        VALUES(market_units),
                                        market_units),
                                 target_units_per_day =
                                     IF(operator_locked = 0,
                                        VALUES(target_units_per_day),
                                        target_units_per_day),
                                 minimum_multiplier =
                                     IF(operator_locked = 0,
                                        VALUES(minimum_multiplier),
                                        minimum_multiplier),
                                 maximum_multiplier =
                                     IF(operator_locked = 0,
                                        VALUES(maximum_multiplier),
                                        maximum_multiplier),
                                 buyback_multiplier =
                                     IF(operator_locked = 0,
                                        VALUES(buyback_multiplier),
                                        buyback_multiplier),
                                 enchant_buyback_multiplier =
                                     IF(operator_locked = 0,
                                        VALUES(enchant_buyback_multiplier),
                                        enchant_buyback_multiplier),
                                 price_source =
                                     IF(operator_locked = 0,
                                        VALUES(price_source),
                                        price_source),
                                 auto_sell_approved =
                                     IF(operator_locked = 0,
                                        VALUES(auto_sell_approved),
                                        auto_sell_approved),
                                 activation_state =
                                     IF(operator_locked = 0,
                                        VALUES(activation_state),
                                        activation_state),
                                 catalog_revision =
                                     IF(operator_locked = 0,
                                        VALUES(catalog_revision),
                                        catalog_revision),
                                 updated_at =
                                     IF(operator_locked = 0,
                                        VALUES(updated_at),
                                        updated_at)
                             """.formatted(table))) {
            for (CatalogSeed seed : seeds) {
                statement.setString(
                        1,
                        seed.material()
                );
                statement.setString(
                        2,
                        seed.category()
                );
                statement.setLong(
                        3,
                        seed.basePriceCents()
                );
                statement.setBoolean(
                        4,
                        seed.serverSellEnabled()
                );
                statement.setBoolean(
                        5,
                        seed.marketEnabled()
                );
                statement.setString(
                        6,
                        seed.marketKey()
                );
                statement.setLong(
                        7,
                        seed.marketUnits()
                );
                statement.setLong(
                        8,
                        seed.targetUnitsPerDay()
                );
                statement.setDouble(
                        9,
                        seed.minimumMultiplier()
                );
                statement.setDouble(
                        10,
                        seed.maximumMultiplier()
                );
                statement.setDouble(
                        11,
                        seed.buybackMultiplier()
                );
                statement.setDouble(
                        12,
                        seed.enchantBuybackMultiplier()
                );
                statement.setString(
                        13,
                        seed.priceSource()
                );
                statement.setBoolean(
                        14,
                        seed.autoSellApproved()
                );
                statement.setString(
                        15,
                        seed.activationState()
                );
                statement.setBoolean(
                        16,
                        seed.operatorLocked()
                );
                statement.setInt(
                        17,
                        seed.catalogRevision()
                );
                statement.setLong(
                        18,
                        now
                );
                statement.setLong(
                        19,
                        now
                );
                statement.addBatch();
            }

            statement.executeBatch();
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

        String name =
                material.name();

        return !name.startsWith("LEGACY_")
                && !name.startsWith("POTTED_")
                && !name.endsWith("_WALL_HEAD")
                && !name.endsWith("_WALL_SKULL")
                && !name.endsWith("_SPAWN_EGG");
    }

    private Set<Material> configuredBlockedMaterials(
            FileConfiguration config
    ) {
        Set<Material> result =
                EnumSet.noneOf(
                        Material.class
                );

        for (String raw
                : config.getStringList(
                "settings.blocked-items"
        )) {
            Material material =
                    Material.matchMaterial(raw);

            if (material != null) {
                result.add(material);
            }
        }

        return Set.copyOf(result);
    }


    private boolean defaultMarketEnabled(
            String category
    ) {
        return switch (category) {
            case "blocks",
                 "ores",
                 "wood",
                 "farming",
                 "mob_drops",
                 "nether",
                 "end" -> true;
            default -> false;
        };
    }

    private String value(
            FileConfiguration configuration,
            String path,
            String fallback
    ) {
        return nonBlank(
                configuration.getString(path),
                fallback
        );
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
            String raw
    ) {
        String normalized =
                nonBlank(
                        raw,
                        DEFAULT_PREFIX
                ).toLowerCase(
                        Locale.ROOT
                );

        if (!normalized.matches(
                "[a-z0-9_]{1,40}"
        )) {
            return DEFAULT_PREFIX;
        }

        return normalized;
    }

    private String normalizeCategory(
            String raw
    ) {
        if (raw == null
                || raw.isBlank()) {
            return "misc";
        }

        return raw.toLowerCase(
                Locale.ROOT
        )
                .replace("-", "_")
                .replace(" ", "_");
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

    private long gcd(
            long first,
            long second
    ) {
        long a =
                Math.abs(first);
        long b =
                Math.abs(second);

        while (b != 0L) {
            long remainder =
                    a % b;
            a = b;
            b = remainder;
        }

        return Math.max(
                1L,
                a
        );
    }

    private long lcm(
            long first,
            long second
    ) {
        if (first <= 0L
                || second <= 0L) {
            return 1L;
        }

        long reduced =
                first / gcd(
                        first,
                        second
                );

        return safeMultiply(
                reduced,
                second
        );
    }

    private enum PriceSource {
        CURATED,
        GENERATED_COMMODITY,
        GENERATED_RECIPE,
        GENERATED_CATEGORY,
        VARIANT_SAFE
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
    }

    private record DatabaseAudit(
            boolean ready,
            int expectedRows,
            int validRows,
            int sellEnabledRows,
            int autoApprovedRows,
            int reviewRows,
            int missingRows,
            int invalidRows
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

    private record CatalogBuild(
            List<CatalogSeed> seeds,
            CatalogSummary summary
    ) {
    }

    private record CatalogSummary(
            int total,
            int curated,
            int commodityGenerated,
            int recipeGenerated,
            int categoryGenerated,
            int variantSafe,
            int floorRecipeSafe,
            int arbitrageReview,
            int commodityGroups
    ) {
    }

    private record RecipeSeed(
            Material output,
            int outputAmount,
            List<IngredientChoice> ingredients
    ) {
    }

    private record IngredientChoice(
            List<Material> materials
    ) {
    }

    private record SimpleConversion(
            Material input,
            long inputAmount,
            Material output,
            long outputAmount
    ) {
    }

    private record CommodityBuild(
            Map<Material, CommodityInfo> info,
            Set<Integer> reversibleRecipeIndexes,
            int groupCount
    ) {
    }

    private record CommodityInfo(
            String marketKey,
            long marketUnits
    ) {
    }

    private record RatioEdge(
            Material to,
            long numerator,
            long denominator
    ) {
    }

    private record Fraction(
            long numerator,
            long denominator
    ) {

        private static final Fraction ONE =
                new Fraction(
                        1L,
                        1L
                );

        private Fraction {
            if (numerator <= 0L
                    || denominator <= 0L) {
                numerator = 1L;
                denominator = 1L;
            } else {
                long divisor =
                        gcdStatic(
                                numerator,
                                denominator
                        );
                numerator /= divisor;
                denominator /= divisor;
            }
        }

        private Fraction multiply(
                long rawNumerator,
                long rawDenominator
        ) {
            long leftNumerator =
                    numerator;
            long leftDenominator =
                    denominator;
            long rightNumerator =
                    Math.max(
                            1L,
                            rawNumerator
                    );
            long rightDenominator =
                    Math.max(
                            1L,
                            rawDenominator
                    );

            /*
             * Cross-reduce before multiplication to keep reversible vanilla
             * recipe groups far away from long overflow.
             */
            long firstGcd =
                    gcdStatic(
                            leftNumerator,
                            rightDenominator
                    );
            leftNumerator /=
                    firstGcd;
            rightDenominator /=
                    firstGcd;

            long secondGcd =
                    gcdStatic(
                            rightNumerator,
                            leftDenominator
                    );
            rightNumerator /=
                    secondGcd;
            leftDenominator /=
                    secondGcd;

            try {
                return new Fraction(
                        Math.multiplyExact(
                                leftNumerator,
                                rightNumerator
                        ),
                        Math.multiplyExact(
                                leftDenominator,
                                rightDenominator
                        )
                );
            } catch (ArithmeticException exception) {
                return ONE;
            }
        }

        private static long gcdStatic(
                long first,
                long second
        ) {
            long a =
                    Math.abs(first);
            long b =
                    Math.abs(second);

            while (b != 0L) {
                long remainder =
                        a % b;
                a = b;
                b = remainder;
            }

            return Math.max(
                    1L,
                    a
            );
        }
    }
}
