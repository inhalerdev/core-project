package net.mineacle.core.sell.service;

import net.mineacle.core.sell.model.SellCatalogEntry;
import net.mineacle.core.sell.model.SellCatalogSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * Forward-only Sell/Worth revision-10 catalog compiler.
 *
 * <p>Economic references flow from primary/configured values into crafted
 * outputs. The compiler never raises an ingredient merely because a downstream
 * output needs a positive cent value. Reversible conversions and selected
 * 1:1 raw-resource processing families share exact commodity units. All other
 * trusted recipes derive or cap outputs against their cheapest liquidatable
 * input path.</p>
 *
 * <p>Live market movement is intentionally disabled in the compiled bootstrap
 * snapshot. The v10 learner runs separately in shadow mode and will become the
 * only component allowed to publish evidence-backed movement later.</p>
 */
public final class SellCatalogV10Compiler {

    private static final int MAX_FORWARD_PASSES = 128;
    private static final int MAX_SAFETY_PASSES = 256;
    private static final int MAX_VALIDATION_FAILURES = 25;
    private static final double MAX_ONE_CENT_SHARE = 0.05D;

    private final SellService sellService;

    public SellCatalogV10Compiler(SellService sellService) {
        this.sellService = sellService;
    }

    public Compilation compile(FileConfiguration config) {
        Set<Material> blocked = configuredBlockedMaterials(config);
        List<RecipeSeed> recipes = snapshotRecipes();

        List<Material> eligible = new ArrayList<>();

        for (Material material : Material.values()) {
            if (eligibleMaterial(material, blocked)) {
                eligible.add(material);
            }
        }

        eligible.sort(
                Comparator.comparing(Material::name)
        );

        Map<Material, Draft> drafts =
                new EnumMap<>(Material.class);

        for (Material material : eligible) {
            String category = normalizeCategory(
                    sellService.category(material)
            );
            long configuredBase =
                    sellService.baseWorthCents(material);
            boolean explicit =
                    configuredBase > 0L
                            && sellService.isExplicitlyPriced(
                            material
                    );
            long fallback = configuredMoneyCents(
                    config,
                    "fallback-prices." + category,
                    SellPricingPolicy
                            .categoryPolicy(category)
                            .defaultSeedCents()
            );
            long base = explicit
                    ? SellPricingPolicy
                    .curatedLiquidationCents(
                            category,
                            configuredBase
                    )
                    : SellPricingPolicy
                    .automaticSeedCents(
                            category,
                            fallback
                    );

            Draft draft = new Draft(
                    material,
                    category,
                    Math.max(
                            SellPricingPolicy.MINIMUM_UNIT_CENTS,
                            base
                    ),
                    explicit,
                    explicit
                            ? PriceSource.CURATED
                            : PriceSource.GENERATED_CATEGORY
            );

            if (SellVariantValuationService
                    .supportsMaterial(material)) {
                draft.baseCents = Math.max(
                        SellPricingPolicy.MINIMUM_UNIT_CENTS,
                        SellVariantValuationService
                                .catalogBaseCents(material)
                );
                draft.source = PriceSource.VARIANT_REQUIRED;
                draft.variant = true;
            }

            drafts.put(material, draft);
        }

        CommodityBuild commodities =
                discoverCommodities(
                        eligible,
                        recipes,
                        drafts
                );

        applyCommodityAuthority(
                drafts,
                commodities
        );

        Map<Material, List<RecipeSeed>> byOutput =
                trustedOneWayRecipes(
                        recipes,
                        commodities
                );

        Set<RecipeSeed> cyclicRecipes =
                cyclicRecipes(byOutput);

        markDerivedCandidates(
                drafts,
                byOutput,
                commodities
        );

        forwardDerive(
                drafts,
                byOutput,
                cyclicRecipes
        );

        resolveUnpricedFallbacks(
                config,
                drafts
        );

        safetyClamp(
                drafts,
                byOutput,
                commodities,
                cyclicRecipes
        );

        return buildSnapshot(
                eligible,
                drafts,
                recipes,
                commodities.groupCount(),
                cyclicRecipes.size()
        );
    }

    private void markDerivedCandidates(
            Map<Material, Draft> drafts,
            Map<Material, List<RecipeSeed>> byOutput,
            CommodityBuild commodities
    ) {
        for (Material output : byOutput.keySet()) {
            Draft draft = drafts.get(output);

            if (draft == null
                    || draft.explicit
                    || draft.variant
                    || commodities.info().containsKey(output)) {
                continue;
            }

            draft.baseCents = 0L;
            draft.source = PriceSource.GENERATED_RECIPE;
            draft.derivedCandidate = true;
        }
    }

    private void forwardDerive(
            Map<Material, Draft> drafts,
            Map<Material, List<RecipeSeed>> byOutput,
            Set<RecipeSeed> cyclicRecipes
    ) {
        for (int pass = 0;
             pass < MAX_FORWARD_PASSES;
             pass++) {
            boolean changed = false;

            for (Map.Entry<Material, List<RecipeSeed>> entry
                    : byOutput.entrySet()) {
                Draft output = drafts.get(
                        entry.getKey()
                );

                if (output == null
                        || !output.derivedCandidate
                        || output.baseCents > 0L
                        || !output.safe) {
                    continue;
                }

                long ceiling = cheapestRecipeCeiling(
                        entry.getValue(),
                        drafts,
                        cyclicRecipes
                );

                if (ceiling <= 0L
                        || ceiling == Long.MAX_VALUE) {
                    continue;
                }

                output.baseCents = ceiling;
                output.source =
                        PriceSource.GENERATED_RECIPE;
                changed = true;
            }

            if (!changed) {
                break;
            }
        }
    }

    private void resolveUnpricedFallbacks(
            FileConfiguration config,
            Map<Material, Draft> drafts
    ) {
        for (Draft draft : drafts.values()) {
            if (draft.baseCents > 0L) {
                continue;
            }

            long fallback = configuredMoneyCents(
                    config,
                    "fallback-prices." + draft.category,
                    SellPricingPolicy
                            .categoryPolicy(draft.category)
                            .defaultSeedCents()
            );

            draft.baseCents = Math.max(
                    SellPricingPolicy.MINIMUM_UNIT_CENTS,
                    SellPricingPolicy
                            .automaticSeedCents(
                                    draft.category,
                                    fallback
                            )
            );
            draft.source =
                    PriceSource.GENERATED_CATEGORY;
            draft.fallbackAfterDerivation = true;
        }
    }

    private void safetyClamp(
            Map<Material, Draft> drafts,
            Map<Material, List<RecipeSeed>> byOutput,
            CommodityBuild commodities,
            Set<RecipeSeed> cyclicRecipes
    ) {
        for (int pass = 0;
             pass < MAX_SAFETY_PASSES;
             pass++) {
            boolean changed = false;

            for (Map.Entry<Material, List<RecipeSeed>> entry
                    : byOutput.entrySet()) {
                Material outputMaterial =
                        entry.getKey();
                Draft output = drafts.get(
                        outputMaterial
                );

                if (output == null
                        || !output.safe
                        || output.variant) {
                    continue;
                }

                long ceiling = cheapestRecipeCeiling(
                        entry.getValue(),
                        drafts,
                        cyclicRecipes
                );

                if (ceiling == Long.MAX_VALUE) {
                    continue;
                }

                if (ceiling <= 0L) {
                    CommodityInfo commodity =
                            commodities.info().get(
                                    outputMaterial
                            );

                    if (commodity == null) {
                        if (output.safe) {
                            output.safe = false;
                            changed = true;
                        }
                    } else if (markCommodityUnsafe(
                            drafts,
                            commodities,
                            commodity.marketKey()
                    )) {
                        changed = true;
                    }
                    continue;
                }

                CommodityInfo commodity =
                        commodities.info().get(
                                outputMaterial
                        );

                if (commodity != null) {
                    long unitCeiling =
                            ceiling
                                    / Math.max(
                                    1L,
                                    commodity.marketUnits()
                            );

                    if (unitCeiling <= 0L) {
                        if (markCommodityUnsafe(
                                drafts,
                                commodities,
                                commodity.marketKey()
                        )) {
                            changed = true;
                        }
                        continue;
                    }

                    if (lowerCommodityUnitPrice(
                            drafts,
                            commodities,
                            commodity.marketKey(),
                            unitCeiling
                    )) {
                        changed = true;
                    }
                    continue;
                }

                if (output.baseCents > ceiling) {
                    output.baseCents = ceiling;
                    output.recipeCapped = true;

                    if (!output.explicit) {
                        output.source =
                                PriceSource
                                        .GENERATED_RECIPE;
                    }

                    changed = true;
                }
            }

            if (!changed) {
                break;
            }
        }
    }

    private long cheapestRecipeCeiling(
            List<RecipeSeed> recipes,
            Map<Material, Draft> drafts,
            Set<RecipeSeed> cyclicRecipes
    ) {
        long cheapest = Long.MAX_VALUE;
        boolean complete = false;

        for (RecipeSeed recipe : recipes) {
            long inputBudget =
                    ingredientNetBudget(
                            recipe,
                            drafts
                    );

            if (inputBudget < 0L
                    || inputBudget == Long.MAX_VALUE) {
                continue;
            }

            complete = true;

            double retention =
                    cyclicRecipes.contains(recipe)
                            ? 1.0D
                            : SellPricingPolicy
                            .ONE_WAY_RECIPE_RETENTION;

            long retained =
                    SellPricingPolicy
                            .outputBaseCeilingCents(
                                    inputBudget,
                                    recipe.outputAmount(),
                                    1.0D,
                                    retention
                            );

            /*
             * If the normal haircut rounds below one cent, allow the strict
             * no-profit cent boundary. This handles precision only; it never
             * permits an output stack to exceed its inputs.
             */
            if (retained <= 0L) {
                retained =
                        hardNoProfitUnitCeiling(
                                inputBudget,
                                recipe.outputAmount()
                        );
            }

            cheapest = Math.min(
                    cheapest,
                    retained
            );
        }

        return complete
                ? cheapest
                : Long.MAX_VALUE;
    }

    private long ingredientNetBudget(
            RecipeSeed recipe,
            Map<Material, Draft> drafts
    ) {
        long total = 0L;

        for (IngredientChoice choice
                : recipe.ingredients()) {
            long cheapest =
                    Long.MAX_VALUE;

            for (Material material
                    : choice.materials()) {
                Draft input =
                        drafts.get(material);

                if (input == null
                        || !input.safe
                        || input.baseCents <= 0L) {
                    continue;
                }

                long net =
                        input.baseCents;

                if (recipe.craftingRemainders()) {
                    Material remainder =
                            material
                                    .getCraftingRemainingItem();

                    if (remainder != null
                            && remainder != Material.AIR) {
                        Draft returned =
                                drafts.get(remainder);

                        if (returned != null
                                && returned.safe
                                && returned.baseCents > 0L) {
                            net = Math.max(
                                    0L,
                                    net
                                            - returned
                                            .baseCents
                            );
                        }
                    }
                }

                cheapest = Math.min(
                        cheapest,
                        net
                );
            }

            if (cheapest == Long.MAX_VALUE) {
                return -1L;
            }

            total = safeAdd(
                    total,
                    cheapest
            );

            if (total == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
        }

        return total;
    }

    private Map<Material, List<RecipeSeed>>
    trustedOneWayRecipes(
            List<RecipeSeed> recipes,
            CommodityBuild commodities
    ) {
        Map<Material, List<RecipeSeed>> result =
                new EnumMap<>(Material.class);

        for (int index = 0;
             index < recipes.size();
             index++) {
            if (commodities
                    .equivalentRecipeIndexes()
                    .contains(index)) {
                continue;
            }

            RecipeSeed recipe =
                    recipes.get(index);

            if (untrustedCatalogRecipe(recipe)
                    || SellVariantValuationService
                    .supportsMaterial(
                            recipe.output()
                    )) {
                continue;
            }

            result.computeIfAbsent(
                    recipe.output(),
                    ignored -> new ArrayList<>()
            ).add(recipe);
        }

        Map<Material, List<RecipeSeed>> immutable =
                new EnumMap<>(Material.class);

        for (Map.Entry<Material, List<RecipeSeed>>
                entry : result.entrySet()) {
            immutable.put(
                    entry.getKey(),
                    List.copyOf(entry.getValue())
            );
        }

        return Map.copyOf(immutable);
    }

    private Set<RecipeSeed> cyclicRecipes(
            Map<Material, List<RecipeSeed>> byOutput
    ) {
        Map<Material, Set<Material>> graph =
                new EnumMap<>(Material.class);
        List<RecipeSeed> recipes =
                new ArrayList<>();

        for (List<RecipeSeed> outputs
                : byOutput.values()) {
            for (RecipeSeed recipe : outputs) {
                recipes.add(recipe);

                for (IngredientChoice choice
                        : recipe.ingredients()) {
                    for (Material ingredient
                            : choice.materials()) {
                        graph.computeIfAbsent(
                                ingredient,
                                ignored -> EnumSet
                                        .noneOf(
                                                Material.class
                                        )
                        ).add(
                                recipe.output()
                        );
                    }
                }
            }
        }

        Set<RecipeSeed> cyclic =
                new HashSet<>();

        for (RecipeSeed recipe : recipes) {
            boolean found = false;

            for (IngredientChoice choice
                    : recipe.ingredients()) {
                for (Material ingredient
                        : choice.materials()) {
                    if (ingredient
                            == recipe.output()
                            || reachable(
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

    private boolean reachable(
            Material start,
            Material target,
            Map<Material, Set<Material>> graph
    ) {
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
            Material current =
                    queue.remove();

            for (Material next
                    : graph.getOrDefault(
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

    private CommodityBuild discoverCommodities(
            List<Material> eligible,
            List<RecipeSeed> recipes,
            Map<Material, Draft> drafts
    ) {
        Map<Integer, SimpleConversion> simple =
                new HashMap<>();
        Map<ConversionKey, List<Integer>> byPair =
                new HashMap<>();

        for (int index = 0;
             index < recipes.size();
             index++) {
            RecipeSeed recipe =
                    recipes.get(index);

            if (untrustedCatalogRecipe(recipe)) {
                continue;
            }

            SimpleConversion conversion =
                    simpleConversion(recipe);

            if (conversion == null) {
                continue;
            }

            simple.put(
                    index,
                    conversion
            );
            byPair.computeIfAbsent(
                    new ConversionKey(
                            conversion.input(),
                            conversion.output()
                    ),
                    ignored -> new ArrayList<>()
            ).add(index);
        }

        Set<Integer> equivalentRecipes =
                new HashSet<>();
        Map<Material, Set<RatioEdge>> graph =
                new EnumMap<>(Material.class);

        for (Map.Entry<Integer, SimpleConversion>
                entry : simple.entrySet()) {
            int leftIndex =
                    entry.getKey();
            SimpleConversion left =
                    entry.getValue();
            List<Integer> reverse =
                    byPair.getOrDefault(
                            new ConversionKey(
                                    left.output(),
                                    left.input()
                            ),
                            List.of()
                    );

            for (int rightIndex : reverse) {
                if (leftIndex >= rightIndex) {
                    continue;
                }

                SimpleConversion right =
                        simple.get(rightIndex);

                if (right == null) {
                    continue;
                }

                long leftMass =
                        safeMultiply(
                                left.inputAmount(),
                                right.inputAmount()
                        );
                long rightMass =
                        safeMultiply(
                                left.outputAmount(),
                                right.outputAmount()
                        );

                if (leftMass <= 0L
                        || leftMass != rightMass) {
                    continue;
                }

                equivalentRecipes.add(
                        leftIndex
                );
                equivalentRecipes.add(
                        rightIndex
                );

                addRatioEdge(
                        graph,
                        left.input(),
                        left.output(),
                        left.inputAmount(),
                        left.outputAmount()
                );
                addRatioEdge(
                        graph,
                        left.output(),
                        left.input(),
                        left.outputAmount(),
                        left.inputAmount()
                );
            }
        }

        /*
         * Recognized 1:1 raw-resource processing is economically equivalent:
         * ore/raw material -> refined ingot/scrap does not create server value.
         * This keeps primary mining references coherent without backwards
         * raising arbitrary ingredients through the general recipe graph.
         */
        for (int index = 0;
             index < recipes.size();
             index++) {
            RecipeSeed recipe =
                    recipes.get(index);

            if (untrustedCatalogRecipe(recipe)
                    || !recipe.cooking()
                    || recipe.outputAmount() != 1
                    || recipe.ingredients().size() != 1) {
                continue;
            }

            IngredientChoice choice =
                    recipe.ingredients().getFirst();

            if (choice.materials().size() != 1) {
                continue;
            }

            Material input =
                    choice.materials().getFirst();
            Material output =
                    recipe.output();

            if (!processingCommodityEligible(
                    input,
                    output,
                    drafts
            )) {
                continue;
            }

            equivalentRecipes.add(index);

            addRatioEdge(
                    graph,
                    input,
                    output,
                    1L,
                    1L
            );
            addRatioEdge(
                    graph,
                    output,
                    input,
                    1L,
                    1L
            );
        }

        Map<Material, CommodityInfo> info =
                new EnumMap<>(Material.class);
        Set<Material> visited =
                EnumSet.noneOf(Material.class);
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

            if (ratios.size() < 2
                    || !consistentComponent(
                    ratios,
                    graph
            )) {
                continue;
            }

            Map<Material, Long> units =
                    integerUnits(ratios);

            if (units.size() < 2) {
                continue;
            }

            visited.addAll(
                    units.keySet()
            );
            groups++;

            Material keyMaterial =
                    units.entrySet()
                            .stream()
                            .min(
                                    Comparator
                                            .comparingLong(
                                                    (
                                                            Map.Entry<
                                                                    Material,
                                                                    Long
                                                                    >
                                                                    value
                                                    ) ->
                                                            value
                                                                    .getValue()
                                            )
                                            .thenComparing(
                                                    value ->
                                                            value
                                                                    .getKey()
                                                                    .name()
                                            )
                            )
                            .map(
                                    Map.Entry::getKey
                            )
                            .orElse(start);

            String marketKey =
                    keyMaterial.name();

            for (Map.Entry<Material, Long>
                    unit : units.entrySet()) {
                info.put(
                        unit.getKey(),
                        new CommodityInfo(
                                marketKey,
                                Math.max(
                                        1L,
                                        unit.getValue()
                                )
                        )
                );
            }
        }

        return new CommodityBuild(
                Map.copyOf(info),
                Set.copyOf(
                        equivalentRecipes
                ),
                groups
        );
    }

    private void applyCommodityAuthority(
            Map<Material, Draft> drafts,
            CommodityBuild commodities
    ) {
        Map<String, List<Material>> groups =
                new LinkedHashMap<>();

        for (Map.Entry<Material, CommodityInfo>
                entry : commodities
                .info()
                .entrySet()) {
            groups.computeIfAbsent(
                    entry.getValue()
                            .marketKey(),
                    ignored -> new ArrayList<>()
            ).add(
                    entry.getKey()
            );
        }

        for (Map.Entry<String, List<Material>>
                group : groups.entrySet()) {
            List<Material> members =
                    group.getValue();
            boolean hasExplicit = false;
            long unitReference =
                    Long.MAX_VALUE;

            for (Material material : members) {
                Draft draft =
                        drafts.get(material);
                CommodityInfo info =
                        commodities.info()
                                .get(material);

                if (draft == null
                        || info == null
                        || draft.baseCents <= 0L) {
                    continue;
                }

                if (draft.explicit) {
                    hasExplicit = true;
                }
            }

            for (Material material : members) {
                Draft draft =
                        drafts.get(material);
                CommodityInfo info =
                        commodities.info()
                                .get(material);

                if (draft == null
                        || info == null
                        || draft.baseCents <= 0L
                        || (hasExplicit
                        && !draft.explicit)) {
                    continue;
                }

                long perUnit =
                        draft.baseCents
                                / Math.max(
                                1L,
                                info.marketUnits()
                        );

                unitReference =
                        Math.clamp(
                                perUnit,
                                1L,
                                unitReference
                        );
            }

            if (unitReference
                    == Long.MAX_VALUE) {
                continue;
            }

            for (Material material : members) {
                Draft draft =
                        drafts.get(material);
                CommodityInfo info =
                        commodities.info()
                                .get(material);

                if (draft == null
                        || info == null) {
                    continue;
                }

                long normalized =
                        safeMultiply(
                                unitReference,
                                Math.max(
                                        1L,
                                        info.marketUnits()
                                )
                        );

                if (draft.explicit
                        && normalized
                        != draft.baseCents) {
                    draft.recipeCapped = true;
                }

                draft.baseCents =
                        Math.max(
                                1L,
                                normalized
                        );
                draft.marketKey =
                        group.getKey();
                draft.marketUnits =
                        Math.max(
                                1L,
                                info.marketUnits()
                        );

                if (!draft.variant) {
                    draft.source =
                            PriceSource
                                    .GENERATED_COMMODITY;
                }
            }
        }
    }

    private boolean lowerCommodityUnitPrice(
            Map<Material, Draft> drafts,
            CommodityBuild commodities,
            String marketKey,
            long unitCeiling
    ) {
        boolean changed = false;
        long safeUnit =
                Math.max(1L, unitCeiling);

        for (Map.Entry<Material, CommodityInfo>
                entry : commodities
                .info()
                .entrySet()) {
            CommodityInfo info =
                    entry.getValue();

            if (!info.marketKey()
                    .equals(marketKey)) {
                continue;
            }

            Draft draft =
                    drafts.get(
                            entry.getKey()
                    );

            if (draft == null
                    || !draft.safe) {
                continue;
            }

            long next =
                    safeMultiply(
                            safeUnit,
                            Math.max(
                                    1L,
                                    info.marketUnits()
                            )
                    );

            if (next < draft.baseCents) {
                draft.baseCents =
                        Math.max(1L, next);
                draft.recipeCapped = true;
                changed = true;
            }
        }

        return changed;
    }

    private boolean markCommodityUnsafe(
            Map<Material, Draft> drafts,
            CommodityBuild commodities,
            String marketKey
    ) {
        boolean changed = false;

        for (Map.Entry<Material, CommodityInfo>
                entry : commodities
                .info()
                .entrySet()) {
            if (!entry.getValue()
                    .marketKey()
                    .equals(marketKey)) {
                continue;
            }

            Draft draft =
                    drafts.get(
                            entry.getKey()
                    );

            if (draft != null
                    && draft.safe) {
                draft.safe = false;
                changed = true;
            }
        }

        return changed;
    }

    private boolean processingCommodityEligible(
            Material input,
            Material output,
            Map<Material, Draft> drafts
    ) {
        Draft inputDraft =
                drafts.get(input);
        Draft outputDraft =
                drafts.get(output);

        if (inputDraft == null
                || outputDraft == null) {
            return false;
        }

        String inputName =
                input.name();
        String outputName =
                output.name();

        boolean resourceInput =
                inputName.startsWith("RAW_")
                        || inputName
                        .contains("_ORE")
                        || inputName
                        .equals(
                                "ANCIENT_DEBRIS"
                        );
        boolean processedOutput =
                outputName.endsWith(
                        "_INGOT"
                )
                        || outputName.equals(
                        "NETHERITE_SCRAP"
                );

        return resourceInput
                && processedOutput
                && (inputDraft.category
                .equals("ores")
                || inputDraft.category
                .equals("nether"))
                && (outputDraft.category
                .equals("ores")
                || outputDraft.category
                .equals("nether"));
    }

    private SimpleConversion simpleConversion(
            RecipeSeed recipe
    ) {
        Material input = null;
        long inputAmount = 0L;

        for (IngredientChoice choice
                : recipe.ingredients()) {
            if (choice.materials().size()
                    != 1) {
                return null;
            }

            Material current =
                    choice.materials()
                            .getFirst();

            if (recipe.craftingRemainders()
                    && current
                    .getCraftingRemainingItem()
                    != null) {
                return null;
            }

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

    private Map<Material, Fraction>
    componentRatios(
            Material start,
            Map<Material, Set<RatioEdge>> graph
    ) {
        Map<Material, Fraction> ratios =
                new EnumMap<>(Material.class);
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
                    Set.of()
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

    private boolean consistentComponent(
            Map<Material, Fraction> ratios,
            Map<Material, Set<RatioEdge>> graph
    ) {
        for (Map.Entry<Material, Fraction>
                entry : ratios.entrySet()) {
            for (RatioEdge edge
                    : graph.getOrDefault(
                    entry.getKey(),
                    Set.of()
            )) {
                Fraction target =
                        ratios.get(
                                edge.to()
                        );

                if (target == null) {
                    continue;
                }

                Fraction expected =
                        entry.getValue()
                                .multiply(
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

        for (Fraction fraction
                : ratios.values()) {
            commonDenominator =
                    lcm(
                            commonDenominator,
                            fraction.denominator()
                    );

            if (commonDenominator <= 0L
                    || commonDenominator
                    == Long.MAX_VALUE) {
                return Map.of();
            }
        }

        Map<Material, Long> raw =
                new EnumMap<>(Material.class);
        long divisor = 0L;

        for (Map.Entry<Material, Fraction>
                entry : ratios.entrySet()) {
            Fraction fraction =
                    entry.getValue();
            long scale =
                    commonDenominator
                            / fraction
                            .denominator();
            long units =
                    safeMultiply(
                            fraction.numerator(),
                            scale
                    );

            if (units <= 0L
                    || units == Long.MAX_VALUE) {
                return Map.of();
            }

            raw.put(
                    entry.getKey(),
                    units
            );
            divisor =
                    divisor == 0L
                            ? units
                            : gcd(
                            divisor,
                            units
                    );
        }

        long commonDivisor =
                Math.max(
                        1L,
                        divisor
                );
        Map<Material, Long> normalized =
                new EnumMap<>(Material.class);

        for (Map.Entry<Material, Long>
                entry : raw.entrySet()) {
            normalized.put(
                    entry.getKey(),
                    Math.max(
                            1L,
                            entry.getValue()
                                    / commonDivisor
                    )
            );
        }

        return Map.copyOf(normalized);
    }

    private void addRatioEdge(
            Map<Material, Set<RatioEdge>> graph,
            Material from,
            Material to,
            long numerator,
            long denominator
    ) {
        graph.computeIfAbsent(
                from,
                ignored -> new HashSet<>()
        ).add(
                new RatioEdge(
                        to,
                        numerator,
                        denominator
                )
        );
    }

    private Compilation buildSnapshot(
            List<Material> eligible,
            Map<Material, Draft> drafts,
            List<RecipeSeed> recipes,
            int commodityGroups,
            int cyclicRecipeCount
    ) {
        Map<Material, SellCatalogEntry> entries =
                new EnumMap<>(Material.class);

        int sellable = 0;
        int references = 0;
        int commodity = 0;
        int derived = 0;
        int fallback = 0;
        int variants = 0;
        int oneCent = 0;
        int unsafe = 0;
        int cappedReferences = 0;

        for (Material material : eligible) {
            Draft draft =
                    drafts.get(material);

            if (draft == null) {
                continue;
            }

            boolean serverSellEnabled =
                    draft.safe
                            && draft.baseCents > 0L;

            if (serverSellEnabled) {
                sellable++;
            } else {
                unsafe++;
            }

            if (draft.baseCents == 1L) {
                oneCent++;
            }

            if (draft.explicit
                    && draft.recipeCapped) {
                cappedReferences++;
            }

            switch (draft.source) {
                case CURATED -> references++;
                case GENERATED_COMMODITY ->
                        commodity++;
                case GENERATED_RECIPE ->
                        derived++;
                case GENERATED_CATEGORY ->
                        fallback++;
                case VARIANT_REQUIRED ->
                        variants++;
            }

            String activation =
                    activationState(
                            draft,
                            serverSellEnabled
                    );

            entries.put(
                    material,
                    new SellCatalogEntry(
                            material,
                            Math.max(
                                    1L,
                                    draft.baseCents
                            ),
                            draft.category,
                            serverSellEnabled,
                            false,
                            draft.marketKey,
                            Math.max(
                                    1L,
                                    draft.marketUnits
                            ),
                            1L,
                            1.0D,
                            1.0D,
                            serverSellEnabled
                                    ? 1.0D
                                    : 0.0D,
                            draft.variant
                                    ? 0.0D
                                    : serverSellEnabled
                                    ? 1.0D
                                    : 0.0D,
                            draft.source.name(),
                            serverSellEnabled,
                            activation,
                            false,
                            SellPricingPolicy
                                    .CATALOG_REVISION
                    )
            );
        }

        SellCatalogSnapshot snapshot =
                new SellCatalogSnapshot(
                        SellPricingPolicy
                                .CATALOG_REVISION,
                        entries.size(),
                        System.currentTimeMillis(),
                        Map.copyOf(entries)
                );

        Summary summary =
                new Summary(
                        entries.size(),
                        sellable,
                        references,
                        commodity,
                        derived,
                        fallback,
                        variants,
                        oneCent,
                        unsafe,
                        commodityGroups,
                        recipes.size(),
                        cyclicRecipeCount,
                        cappedReferences
                );

        List<String> failures =
                validateCandidate(
                        eligible,
                        drafts,
                        recipes,
                        summary
                );

        return new Compilation(
                snapshot,
                summary,
                failures
        );
    }

    private List<String> validateCandidate(
            List<Material> eligible,
            Map<Material, Draft> drafts,
            List<RecipeSeed> recipes,
            Summary summary
    ) {
        List<String> failures =
                new ArrayList<>();

        if (summary.total() != eligible.size()) {
            addValidationFailure(
                    failures,
                    "catalog row count "
                            + summary.total()
                            + " != eligible material count "
                            + eligible.size()
            );
        }

        if (summary.unsafe() > 0
                || summary.sellable()
                != summary.total()) {
            addValidationFailure(
                    failures,
                    summary.unsafe()
                            + " normal catalog material(s) are unsafe or not server-sellable"
            );
        }

        if (summary.total() > 0
                && summary.oneCent()
                / (double) summary.total()
                > MAX_ONE_CENT_SHARE) {
            addValidationFailure(
                    failures,
                    "one-cent values exceed "
                            + Math.round(
                            MAX_ONE_CENT_SHARE
                                    * 100.0D
                    )
                            + "% of the catalog ("
                            + summary.oneCent()
                            + "/"
                            + summary.total()
                            + ")"
            );
        }

        int recipeFailures = 0;

        for (RecipeSeed recipe : recipes) {
            if (untrustedCatalogRecipe(recipe)
                    || SellVariantValuationService
                    .supportsMaterial(
                            recipe.output()
                    )) {
                continue;
            }

            Draft output =
                    drafts.get(
                            recipe.output()
                    );

            if (output == null
                    || !output.safe
                    || output.baseCents <= 0L) {
                recipeFailures++;
                addValidationFailure(
                        failures,
                        "recipe output unavailable: "
                                + recipe.output()
                );
                continue;
            }

            long input =
                    ingredientNetBudget(
                            recipe,
                            drafts
                    );

            if (input < 0L
                    || input == Long.MAX_VALUE) {
                recipeFailures++;
                addValidationFailure(
                        failures,
                        "recipe has no complete liquidatable input: "
                                + recipe.output()
                );
                continue;
            }

            long outputPayout =
                    safeMultiply(
                            output.baseCents,
                            recipe.outputAmount()
                    );

            if (outputPayout
                    == Long.MAX_VALUE
                    || outputPayout > input) {
                recipeFailures++;
                addValidationFailure(
                        failures,
                        "recipe payout exceeds inputs: "
                                + recipe.output()
                                + " "
                                + outputPayout
                                + ">"
                                + input
                );
            }
        }

        if (recipeFailures
                > MAX_VALIDATION_FAILURES) {
            failures.add(
                    "+"
                            + (recipeFailures
                            - MAX_VALIDATION_FAILURES)
                            + " additional recipe validation failure(s)"
            );
        }

        return List.copyOf(failures);
    }

    private void addValidationFailure(
            List<String> failures,
            String failure
    ) {
        if (failures.size()
                >= MAX_VALIDATION_FAILURES) {
            return;
        }

        failures.add(failure);
    }

    private String activationState(
            Draft draft,
            boolean sellEnabled
    ) {
        if (!sellEnabled) {
            return "V10_UNSAFE";
        }

        if (draft.variant) {
            return "V10_VARIANT";
        }

        if (draft.source
                == PriceSource
                .GENERATED_COMMODITY) {
            return draft.recipeCapped
                    ? "V10_COMMODITY_CAPPED"
                    : "V10_COMMODITY";
        }

        if (draft.source
                == PriceSource
                .GENERATED_RECIPE) {
            return "V10_DERIVED";
        }

        if (draft.source
                == PriceSource.CURATED) {
            return draft.recipeCapped
                    ? "V10_REFERENCE_CAPPED"
                    : "V10_REFERENCE";
        }

        return draft.fallbackAfterDerivation
                ? "V10_FALLBACK_AFTER_RECIPE"
                : "V10_FALLBACK";
    }

    private List<RecipeSeed> snapshotRecipes() {
        List<RecipeSeed> recipes =
                new ArrayList<>();
        Iterator<Recipe> iterator =
                Bukkit.recipeIterator();

        while (iterator.hasNext()) {
            RecipeSeed seed =
                    recipeSeed(
                            iterator.next()
                    );

            if (seed != null) {
                recipes.add(seed);
            }
        }

        return List.copyOf(recipes);
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
        boolean cooking = false;
        boolean craftingRemainders = false;

        if (recipe instanceof ShapedRecipe shaped) {
            craftingRemainders = true;
            Map<Character, RecipeChoice> choices =
                    shaped.getChoiceMap();

            for (String row
                    : shaped.getShape()) {
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
            craftingRemainders = true;

            for (RecipeChoice raw
                    : shapeless
                    .getChoiceList()) {
                IngredientChoice choice =
                        ingredientChoice(raw);

                if (choice == null) {
                    return null;
                }

                ingredients.add(choice);
            }
        } else if (recipe
                instanceof CookingRecipe<?> cookingRecipe) {
            IngredientChoice choice =
                    ingredientChoice(
                            cookingRecipe
                                    .getInputChoice()
                    );

            if (choice == null) {
                return null;
            }

            ingredients.add(choice);
            cooking = true;
        } else if (recipe
                instanceof StonecuttingRecipe stonecutting) {
            IngredientChoice choice =
                    ingredientChoice(
                            stonecutting
                                    .getInputChoice()
                    );

            if (choice == null) {
                return null;
            }

            ingredients.add(choice);
        } else if (recipe
                instanceof TransmuteRecipe transmute) {
            craftingRemainders = true;

            IngredientChoice input =
                    ingredientChoice(
                            transmute.getInput()
                    );
            IngredientChoice material =
                    ingredientChoice(
                            transmute.getMaterial()
                    );

            if (input == null
                    || material == null) {
                return null;
            }

            ingredients.add(input);
            ingredients.add(material);
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
                List.copyOf(ingredients),
                cooking,
                craftingRemainders
        );
    }

    @SuppressWarnings("UnstableApiUsage")
    private IngredientChoice ingredientChoice(
            RecipeChoice choice
    ) {
        if (choice
                instanceof RecipeChoice
                .ItemTypeChoice itemTypes) {
            List<Material> values =
                    itemTypes.itemTypes()
                            .resolve(Registry.ITEM)
                            .stream()
                            .map(
                                    type ->
                                            Material
                                                    .matchMaterial(
                                                            type
                                                                    .getKey()
                                                                    .toString()
                                                    )
                            )
                            .filter(
                                    java.util.Objects
                                            ::nonNull
                            )
                            .filter(Material::isItem)
                            .filter(
                                    material ->
                                            material
                                                    != Material.AIR
                            )
                            .distinct()
                            .toList();

            return values.isEmpty()
                    ? null
                    : new IngredientChoice(
                            values,
                            false
                    );
        }

        if (choice
                instanceof RecipeChoice
                .MaterialChoice materials) {
            List<Material> values =
                    materials.getChoices()
                            .stream()
                            .filter(Material::isItem)
                            .filter(
                                    material ->
                                            material
                                                    != Material.AIR
                            )
                            .distinct()
                            .toList();

            return values.isEmpty()
                    ? null
                    : new IngredientChoice(
                            values,
                            false
                    );
        }

        if (choice
                instanceof RecipeChoice
                .ExactChoice exact) {
            boolean untrusted =
                    exact.getChoices()
                            .stream()
                            .anyMatch(
                                    ItemStack::hasItemMeta
                            );
            List<Material> values =
                    exact.getChoices()
                            .stream()
                            .map(ItemStack::getType)
                            .filter(Material::isItem)
                            .filter(
                                    material ->
                                            material
                                                    != Material.AIR
                            )
                            .distinct()
                            .toList();

            return values.isEmpty()
                    ? null
                    : new IngredientChoice(
                            values,
                            untrusted
                    );
        }

        return null;
    }

    private boolean untrustedCatalogRecipe(
            RecipeSeed recipe
    ) {
        if (recipe == null
                || recipe.output() == null
                || recipe.output() == Material.AIR
                || recipe.outputAmount() <= 0
                || recipe.ingredients().isEmpty()) {
            return true;
        }

        for (IngredientChoice choice
                : recipe.ingredients()) {
            if (choice == null
                    || choice.untrusted()
                    || choice.materials().isEmpty()) {
                return true;
            }
        }

        return false;
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
                && !name.startsWith("INFESTED_")
                && !name.endsWith("_WALL_HEAD")
                && !name.endsWith("_WALL_SKULL")
                && !name.endsWith("_SPAWN_EGG")
                && !name.contains(
                "COMMAND_BLOCK"
        );
    }

    private Set<Material> configuredBlockedMaterials(
            FileConfiguration config
    ) {
        Set<Material> result =
                EnumSet.noneOf(Material.class);

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

    private long configuredMoneyCents(
            FileConfiguration config,
            String path,
            long fallback
    ) {
        Object raw =
                config.get(path);

        if (raw == null) {
            return Math.max(
                    0L,
                    fallback
            );
        }

        try {
            return new BigDecimal(
                    String.valueOf(raw)
            )
                    .movePointRight(2)
                    .setScale(
                            0,
                            RoundingMode.HALF_UP
                    )
                    .max(BigDecimal.ZERO)
                    .longValueExact();
        } catch (
                NumberFormatException
                | ArithmeticException exception
        ) {
            return Math.max(
                    0L,
                    fallback
            );
        }
    }

    private long hardNoProfitUnitCeiling(
            long inputBudget,
            int outputAmount
    ) {
        if (inputBudget <= 0L
                || outputAmount <= 0) {
            return 0L;
        }

        return inputBudget
                / outputAmount;
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
                .replace('-', '_')
                .replace(' ', '_');
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
        long a = Math.abs(first);
        long b = Math.abs(second);

        while (b != 0L) {
            long next = a % b;
            a = b;
            b = next;
        }

        return Math.max(1L, a);
    }

    private long lcm(
            long first,
            long second
    ) {
        if (first <= 0L
                || second <= 0L) {
            return Long.MAX_VALUE;
        }

        long divisor =
                gcd(first, second);

        return safeMultiply(
                first / divisor,
                second
        );
    }

    public record Compilation(
            SellCatalogSnapshot snapshot,
            Summary summary,
            List<String> failures
    ) {
        public Compilation {
            failures = failures == null
                    ? List.of()
                    : List.copyOf(failures);
        }

        public boolean ready() {
            return failures.isEmpty();
        }
    }

    public record Summary(
            int total,
            int sellable,
            int references,
            int commodity,
            int derived,
            int fallback,
            int variants,
            int oneCent,
            int unsafe,
            int commodityGroups,
            int recipes,
            int cyclicRecipes,
            int cappedReferences
    ) {
    }

    private enum PriceSource {
        CURATED,
        GENERATED_COMMODITY,
        GENERATED_RECIPE,
        GENERATED_CATEGORY,
        VARIANT_REQUIRED
    }

    private static final class Draft {
        private final String category;
        private long baseCents;
        private final boolean explicit;
        private PriceSource source;
        private boolean safe = true;
        private boolean recipeCapped;
        private boolean variant;
        private boolean derivedCandidate;
        private boolean fallbackAfterDerivation;
        private String marketKey;
        private long marketUnits = 1L;

        private Draft(
                Material material,
                String category,
                long baseCents,
                boolean explicit,
                PriceSource source
        ) {
            this.category = category;
            this.baseCents = baseCents;
            this.explicit = explicit;
            this.source = source;
            this.marketKey =
                    material.name();
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
            ingredients =
                    List.copyOf(ingredients);
        }
    }

    private record IngredientChoice(
            List<Material> materials,
            boolean untrusted
    ) {
        private IngredientChoice {
            materials =
                    List.copyOf(materials);
        }
    }

    private record SimpleConversion(
            Material input,
            long inputAmount,
            Material output,
            long outputAmount
    ) {
    }

    private record ConversionKey(
            Material input,
            Material output
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
            equivalentRecipeIndexes =
                    Set.copyOf(
                            equivalentRecipeIndexes
                    );
        }
    }

    private record Fraction(
            long numerator,
            long denominator
    ) {
        private static final Fraction ONE =
                new Fraction(1L, 1L);

        private Fraction {
            if (denominator == 0L) {
                throw new IllegalArgumentException(
                        "denominator"
                );
            }

            if (denominator < 0L) {
                numerator = -numerator;
                denominator = -denominator;
            }

            long divisor =
                    gcdStatic(
                            Math.abs(numerator),
                            denominator
                    );

            numerator /= divisor;
            denominator /= divisor;
        }

        private Fraction multiply(
                long otherNumerator,
                long otherDenominator
        ) {
            if (otherNumerator <= 0L
                    || otherDenominator <= 0L) {
                return this;
            }

            try {
                return new Fraction(
                        Math.multiplyExact(
                                numerator,
                                otherNumerator
                        ),
                        Math.multiplyExact(
                                denominator,
                                otherDenominator
                        )
                );
            } catch (ArithmeticException exception) {
                return this;
            }
        }

        private static long gcdStatic(
                long first,
                long second
        ) {
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
}
