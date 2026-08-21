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
 * <p>The compiled bootstrap snapshot is the frozen reference authority. Live
 * movement is produced separately through {@link LiveAuthority}, which always
 * reprices from that immutable reference, re-applies structural cent/container
 * floors, propagates recipe ceilings forward, and refuses publication unless
 * the full candidate passes the same recipe-safety audit.</p>
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

    /**
     * Captures the immutable recipe graph used by the live v10 pricing
     * governor. Call this on the server thread after the reference catalog has
     * activated; the returned authority performs no Bukkit registry access and
     * is therefore safe to evaluate on the governor's dedicated worker.
     */
    public LiveAuthority createLiveAuthority(
            SellCatalogSnapshot referenceSnapshot
    ) {
        if (referenceSnapshot == null
                || referenceSnapshot.revision()
                != SellPricingPolicy.CATALOG_REVISION
                || referenceSnapshot.entries().isEmpty()
                || referenceSnapshot.expectedRows()
                != referenceSnapshot.entries().size()) {
            throw new IllegalArgumentException(
                    "referenceSnapshot"
            );
        }

        return new LiveAuthority(
                referenceSnapshot,
                snapshotRecipes()
        );
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

        /*
         * A filled/consumed container must always be worth at least the
         * returned empty container plus one cent. This is an intrinsic
         * material invariant, not backwards recipe propagation. Without it,
         * HONEY_BOTTLE -> SUGAR and MILK_BUCKET -> CAKE can appear to have a
         * zero net input because the generic fallback priced the filled
         * container at or below its reusable remainder.
         */
        normalizeContainerRemainderFloors(
                drafts
        );

        /*
         * Integer cents create one unavoidable feasibility constraint: a
         * simple conversion that produces N sellable output items must carry
         * at least N cents of net input value. Apply only the minimum floor
         * required for one-material conversions. This is currency-precision
         * normalization, not economic backwards pricing: it never preserves a
         * configured output price and never raises multi-input recipe chains.
         */
        normalizeSimpleConversionCentFloors(
                recipes,
                drafts
        );

        /*
         * Container-packed reversible conversions need one additional affine
         * constraint. Example:
         *
         * HONEY_BLOCK + 4 GLASS_BOTTLE -> 4 HONEY_BOTTLE
         *
         * If HONEY_BOTTLE must retain a positive net value above its returned
         * GLASS_BOTTLE, the non-container payload must carry exactly that net
         * value. This keeps reversible container cycles representable without
         * raising arbitrary multi-input ingredients.
         */
        normalizeContainerPackingFloors(
                recipes,
                drafts
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
                        || output.baseCents > 0L) {
                    continue;
                }

                long ceiling = cheapestRecipeCeiling(
                        entry.getValue(),
                        drafts,
                        cyclicRecipes,
                        output.minimumBaseCents
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
                    draft.minimumBaseCents,
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
                        || output.variant) {
                    continue;
                }

                long ceiling = cheapestRecipeCeiling(
                        entry.getValue(),
                        drafts,
                        cyclicRecipes,
                        output.minimumBaseCents
                );

                if (ceiling == Long.MAX_VALUE) {
                    continue;
                }

                if (ceiling <= 0L) {
                    /*
                     * Do not poison the output or its entire commodity family.
                     * The immutable candidate is validated before publication,
                     * so an unsatisfied zero-cent constraint remains an exact
                     * recipe failure instead of cascading into dozens of
                     * "input unavailable" errors.
                     */
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
                        /*
                         * Same atomic-candidate rule as above: preserve the
                         * family for diagnostics and let validation reject the
                         * exact impossible recipe rather than cascading an
                         * unsafe state through every family member.
                         */
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
            Set<RecipeSeed> cyclicRecipes,
            long minimumFloor
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
            long hardCeiling =
                    hardNoProfitUnitCeiling(
                            inputBudget,
                            recipe.outputAmount()
                    );

            /*
             * Retention is a pacing preference; the hard no-profit ceiling is
             * the safety boundary. If integer-cent feasibility requires a
             * slightly higher unit value, allow it only when it still fits
             * beneath that hard boundary. This is what makes conversions such
             * as 3 low-value blocks -> 6 slabs representable without ever
             * permitting craft-to-Sell profit.
             */
            long requiredFloor = Math.max(
                    SellPricingPolicy.MINIMUM_UNIT_CENTS,
                    minimumFloor
            );

            if (hardCeiling >= requiredFloor) {
                retained = Math.clamp(
                        Math.max(retained, requiredFloor),
                        requiredFloor,
                        hardCeiling
                );
            } else {
                /*
                 * Never erase an intrinsic structural/cent floor simply
                 * because another recipe cannot currently support it. Keep
                 * the floor and let the atomic candidate validator reject the
                 * exact incompatible recipe. Container-packing normalization
                 * resolves the legitimate reversible cases before this point.
                 */
                retained = requiredFloor;
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

        includeEquivalentFamilyRecipes(
                recipes,
                info,
                equivalentRecipes
        );

        return new CommodityBuild(
                Map.copyOf(info),
                Set.copyOf(
                        equivalentRecipes
                ),
                groups
        );
    }

    private void includeEquivalentFamilyRecipes(
            List<RecipeSeed> recipes,
            Map<Material, CommodityInfo> info,
            Set<Integer> equivalentRecipes
    ) {
        for (int index = 0;
             index < recipes.size();
             index++) {
            if (equivalentRecipes.contains(index)) {
                continue;
            }

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

            CommodityInfo input =
                    info.get(
                            conversion.input()
                    );
            CommodityInfo output =
                    info.get(
                            conversion.output()
                    );

            if (input == null
                    || output == null
                    || !input.marketKey()
                    .equals(
                            output.marketKey()
                    )) {
                continue;
            }

            long inputUnits =
                    safeMultiply(
                            conversion.inputAmount(),
                            input.marketUnits()
                    );
            long outputUnits =
                    safeMultiply(
                            conversion.outputAmount(),
                            output.marketUnits()
                    );

            if (inputUnits > 0L
                    && inputUnits != Long.MAX_VALUE
                    && inputUnits == outputUnits) {
                equivalentRecipes.add(index);
            }
        }
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

            if (draft == null) {
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

    private void normalizeContainerRemainderFloors(
            Map<Material, Draft> drafts
    ) {
        for (Draft draft : drafts.values()) {
            Material remainder =
                    draft.material
                            .getCraftingRemainingItem();

            if (remainder == null
                    || remainder == Material.AIR) {
                continue;
            }

            Draft returned =
                    drafts.get(remainder);

            if (returned == null
                    || returned.baseCents <= 0L) {
                continue;
            }

            long structuralFloor =
                    safeAdd(
                            returned.baseCents,
                            SellPricingPolicy.MINIMUM_UNIT_CENTS
                    );

            if (structuralFloor == Long.MAX_VALUE
                    || draft.baseCents >= structuralFloor) {
                continue;
            }

            draft.baseCents = structuralFloor;
            draft.minimumBaseCents = Math.max(
                    draft.minimumBaseCents,
                    structuralFloor
            );
            draft.structuralFloorAdjusted = true;
        }
    }

    private void normalizeSimpleConversionCentFloors(
            List<RecipeSeed> recipes,
            Map<Material, Draft> drafts
    ) {
        for (RecipeSeed recipe : recipes) {
            if (untrustedCatalogRecipe(recipe)
                    || recipe.outputAmount() <= 0
                    || recipe.ingredients().isEmpty()) {
                continue;
            }

            Material inputMaterial = null;
            int inputCount = 0;
            boolean simple = true;

            for (IngredientChoice choice : recipe.ingredients()) {
                if (choice.materials().size() != 1) {
                    simple = false;
                    break;
                }

                Material current =
                        choice.materials().getFirst();

                if (inputMaterial == null) {
                    inputMaterial = current;
                } else if (inputMaterial != current) {
                    simple = false;
                    break;
                }

                inputCount++;
            }

            if (!simple
                    || inputMaterial == null
                    || inputCount <= 0
                    || inputMaterial == recipe.output()) {
                continue;
            }

            Draft input = drafts.get(inputMaterial);

            if (input == null
                    || input.baseCents <= 0L) {
                continue;
            }

            long returnedCents = 0L;

            if (recipe.craftingRemainders()) {
                Material remainder =
                        inputMaterial.getCraftingRemainingItem();

                if (remainder != null
                        && remainder != Material.AIR) {
                    Draft returned = drafts.get(remainder);

                    if (returned != null
                            && returned.baseCents > 0L) {
                        returnedCents = returned.baseCents;
                    }
                }
            }

            long requiredNetPerInput =
                    (recipe.outputAmount()
                            + (long) inputCount
                            - 1L)
                            / inputCount;
            long requiredBase =
                    safeAdd(
                            returnedCents,
                            Math.max(
                                    SellPricingPolicy.MINIMUM_UNIT_CENTS,
                                    requiredNetPerInput
                            )
                    );

            if (requiredBase == Long.MAX_VALUE) {
                continue;
            }

            input.minimumBaseCents = Math.max(
                    input.minimumBaseCents,
                    requiredBase
            );

            if (input.baseCents < input.minimumBaseCents) {
                input.baseCents = input.minimumBaseCents;
                input.centFeasibilityAdjusted = true;
            }
        }
    }

    private void normalizeContainerPackingFloors(
            List<RecipeSeed> recipes,
            Map<Material, Draft> drafts
    ) {
        for (RecipeSeed recipe : recipes) {
            if (untrustedCatalogRecipe(recipe)
                    || recipe.outputAmount() <= 0
                    || recipe.ingredients().isEmpty()) {
                continue;
            }

            Draft output =
                    drafts.get(recipe.output());

            if (output == null
                    || output.minimumBaseCents <= 0L) {
                continue;
            }

            Material remainder =
                    recipe.output()
                            .getCraftingRemainingItem();

            if (remainder == null
                    || remainder == Material.AIR) {
                continue;
            }

            Draft returned =
                    drafts.get(remainder);

            if (returned == null
                    || returned.baseCents <= 0L
                    || output.minimumBaseCents
                    <= returned.baseCents) {
                continue;
            }

            int remainderInputs = 0;
            int payloadInputs = 0;
            Material payloadMaterial = null;
            boolean compatible = true;

            for (IngredientChoice choice : recipe.ingredients()) {
                if (choice.materials().size() != 1) {
                    compatible = false;
                    break;
                }

                Material material =
                        choice.materials().getFirst();

                if (material == remainder) {
                    remainderInputs++;
                    continue;
                }

                if (payloadMaterial == null) {
                    payloadMaterial = material;
                } else if (payloadMaterial != material) {
                    compatible = false;
                    break;
                }

                payloadInputs++;
            }

            /*
             * This rule is deliberately narrow: the recipe must package one
             * homogeneous payload with exactly one empty remainder container
             * for every produced filled container. That covers reversible
             * container cycles such as Honey Block <-> Honey Bottles without
             * becoming a general backwards recipe-pricing rule.
             */
            if (!compatible
                    || payloadMaterial == null
                    || payloadInputs <= 0
                    || remainderInputs != recipe.outputAmount()) {
                continue;
            }

            Draft payload =
                    drafts.get(payloadMaterial);

            if (payload == null
                    || payload.baseCents <= 0L) {
                continue;
            }

            long netPerOutput =
                    output.minimumBaseCents
                            - returned.baseCents;
            long requiredPayloadTotal =
                    safeMultiply(
                            netPerOutput,
                            recipe.outputAmount()
                    );

            if (requiredPayloadTotal <= 0L
                    || requiredPayloadTotal == Long.MAX_VALUE) {
                continue;
            }

            long requiredPayloadPerInput =
                    requiredPayloadTotal
                            / payloadInputs;

            if (requiredPayloadTotal
                    % payloadInputs != 0L) {
                requiredPayloadPerInput++;
            }

            payload.minimumBaseCents =
                    Math.max(
                            payload.minimumBaseCents,
                            requiredPayloadPerInput
                    );

            if (payload.baseCents
                    < payload.minimumBaseCents) {
                payload.baseCents =
                        payload.minimumBaseCents;
                payload.centFeasibilityAdjusted = true;
            }
        }
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
                    draft.baseCents > 0L;

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

        if (draft.centFeasibilityAdjusted) {
            return draft.explicit
                    ? "V10_REFERENCE_CENT_FLOOR"
                    : "V10_CENT_FLOOR";
        }

        if (draft.structuralFloorAdjusted) {
            return draft.explicit
                    ? "V10_REFERENCE_CONTAINER_FLOOR"
                    : "V10_CONTAINER_FLOOR";
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


    private void captureContainerRemainderMinimums(
            Map<Material, Draft> drafts
    ) {
        for (Draft draft : drafts.values()) {
            Material remainder =
                    draft.material
                            .getCraftingRemainingItem();

            if (remainder == null
                    || remainder == Material.AIR) {
                continue;
            }

            Draft returned =
                    drafts.get(remainder);

            if (returned == null
                    || returned.baseCents <= 0L) {
                continue;
            }

            long structuralFloor =
                    safeAdd(
                            returned.baseCents,
                            SellPricingPolicy.MINIMUM_UNIT_CENTS
                    );

            if (structuralFloor == Long.MAX_VALUE) {
                continue;
            }

            draft.minimumBaseCents = Math.max(
                    draft.minimumBaseCents,
                    structuralFloor
            );
        }
    }


    /**
     * Immutable, restart-stable authority for evidence-backed live repricing.
     *
     * <p>Every call starts from the frozen revision-10 reference snapshot.
     * Requested multipliers can never compound from the previous live
     * generation. Commodity ratios are kept exact, structural floors are
     * restored, and one-way recipe safety is propagated forward before the
     * candidate is audited.</p>
     */
    public final class LiveAuthority {

        private static final double MINIMUM_DEFENSIVE_MULTIPLIER =
                0.10D;
        private static final double MAXIMUM_DEFENSIVE_MULTIPLIER =
                4.00D;

        private final SellCatalogSnapshot referenceSnapshot;
        private final List<RecipeSeed> recipes;
        private final List<Material> eligible;
        private final CommodityBuild commodities;
        private final Map<Material, List<RecipeSeed>> byOutput;
        private final Set<RecipeSeed> cyclicRecipes;
        private final Map<String, BigDecimal> referenceUnitCents;

        private LiveAuthority(
                SellCatalogSnapshot referenceSnapshot,
                List<RecipeSeed> recipes
        ) {
            this.referenceSnapshot =
                    referenceSnapshot;
            this.recipes =
                    List.copyOf(recipes);

            List<Material> materials =
                    new ArrayList<>(
                            referenceSnapshot
                                    .entries()
                                    .keySet()
                    );
            materials.sort(
                    Comparator.comparing(
                            Material::name
                    )
            );
            this.eligible =
                    List.copyOf(materials);

            this.commodities =
                    commodityBuildFromReference(
                            referenceSnapshot,
                            this.recipes
                    );
            this.byOutput =
                    trustedOneWayRecipes(
                            this.recipes,
                            this.commodities
                    );
            this.cyclicRecipes =
                    cyclicRecipes(
                            this.byOutput
                    );
            this.referenceUnitCents =
                    SellCatalogV10Compiler.this.referenceUnitCents(
                            referenceSnapshot
                    );
        }

        public LiveCompilation reprice(
                Map<String, Double> requestedMultipliers
        ) {
            Map<String, Double> requested =
                    sanitizeRequestedMultipliers(
                            requestedMultipliers
                    );
            Map<Material, Draft> drafts =
                    draftsFromReference(
                            referenceSnapshot
                    );

            applyLiveMultipliers(
                    drafts,
                    requested
            );

            /*
             * Re-establish all intrinsic integer-cent/container constraints
             * after applying market pressure. These floors are structural;
             * they are never demand-driven backwards price propagation.
             */
            captureContainerRemainderMinimums(
                    drafts
            );
            normalizeContainerRemainderFloors(
                    drafts
            );
            normalizeSimpleConversionCentFloors(
                    recipes,
                    drafts
            );
            normalizeContainerPackingFloors(
                    recipes,
                    drafts
            );

            safetyClamp(
                    drafts,
                    byOutput,
                    commodities,
                    cyclicRecipes
            );

            Compilation validation =
                    buildSnapshot(
                            eligible,
                            drafts,
                            recipes,
                            commodities.groupCount(),
                            cyclicRecipes.size()
                    );

            if (!validation.ready()) {
                return new LiveCompilation(
                        null,
                        Map.of(),
                        validation.failures()
                );
            }

            SellCatalogSnapshot liveSnapshot =
                    buildLiveSnapshot(
                            drafts
                    );

            return new LiveCompilation(
                    liveSnapshot,
                    effectiveMultipliers(
                            requested,
                            liveSnapshot
                    ),
                    List.of()
            );
        }

        private Map<String, Double>
        sanitizeRequestedMultipliers(
                Map<String, Double> raw
        ) {
            if (raw == null
                    || raw.isEmpty()) {
                return Map.of();
            }

            Map<String, Double> result =
                    new LinkedHashMap<>();

            for (Map.Entry<String, Double>
                    entry : raw.entrySet()) {
                String key =
                        normalizeMarketKey(
                                entry.getKey()
                        );
                Double value =
                        entry.getValue();

                if (key.isBlank()
                        || value == null
                        || !Double.isFinite(value)
                        || value <= 0.0D) {
                    continue;
                }

                result.put(
                        key,
                        Math.clamp(
                                value,
                                MINIMUM_DEFENSIVE_MULTIPLIER,
                                MAXIMUM_DEFENSIVE_MULTIPLIER
                        )
                );
            }

            return Map.copyOf(result);
        }

        private void applyLiveMultipliers(
                Map<Material, Draft> drafts,
                Map<String, Double> requested
        ) {
            for (Map.Entry<Material, Draft>
                    draftEntry : drafts.entrySet()) {
                Material material =
                        draftEntry.getKey();
                Draft draft =
                        draftEntry.getValue();
                SellCatalogEntry reference =
                        referenceSnapshot
                                .entries()
                                .get(material);

                if (reference == null
                        || draft.variant) {
                    continue;
                }

                String marketKey =
                        normalizeMarketKey(
                                reference.marketKey()
                        );
                double multiplier =
                        requested.getOrDefault(
                                marketKey,
                                1.0D
                        );
                long scaled;

                CommodityInfo commodity =
                        commodities.info()
                                .get(material);

                if (commodity != null) {
                    BigDecimal unitReference =
                            referenceUnitCents
                                    .get(
                                            commodity
                                                    .marketKey()
                                    );

                    if (unitReference == null
                            || unitReference.signum()
                            <= 0) {
                        scaled =
                                reference.baseCents();
                    } else {
                        long unit =
                                scaleCents(
                                        unitReference,
                                        multiplier
                                );
                        scaled =
                                safeMultiply(
                                        unit,
                                        Math.max(
                                                1L,
                                                commodity
                                                        .marketUnits()
                                        )
                                );
                    }
                } else {
                    scaled =
                            scaleCents(
                                    BigDecimal.valueOf(
                                            reference
                                                    .baseCents()
                                    ),
                                    multiplier
                            );
                }

                if (scaled <= 0L
                        || scaled == Long.MAX_VALUE) {
                    scaled =
                            reference.baseCents();
                }

                draft.baseCents =
                        Math.max(
                                SellPricingPolicy
                                        .MINIMUM_UNIT_CENTS,
                                scaled
                        );
            }
        }

        private long scaleCents(
                BigDecimal reference,
                double multiplier
        ) {
            if (reference == null
                    || reference.signum() <= 0
                    || !Double.isFinite(multiplier)
                    || multiplier <= 0.0D) {
                return SellPricingPolicy
                        .MINIMUM_UNIT_CENTS;
            }

            try {
                return reference
                        .multiply(
                                BigDecimal.valueOf(
                                        multiplier
                                )
                        )
                        .setScale(
                                0,
                                RoundingMode.HALF_UP
                        )
                        .max(
                                BigDecimal.valueOf(
                                        SellPricingPolicy
                                                .MINIMUM_UNIT_CENTS
                                )
                        )
                        .longValueExact();
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        }

        private SellCatalogSnapshot buildLiveSnapshot(
                Map<Material, Draft> drafts
        ) {
            Map<Material, SellCatalogEntry> entries =
                    new EnumMap<>(
                            Material.class
                    );

            for (Material material : eligible) {
                SellCatalogEntry reference =
                        referenceSnapshot
                                .entries()
                                .get(material);
                Draft draft =
                        drafts.get(material);

                if (reference == null
                        || draft == null) {
                    continue;
                }

                long base =
                        Math.max(
                                SellPricingPolicy
                                        .MINIMUM_UNIT_CENTS,
                                draft.baseCents
                        );
                boolean changed =
                        base != reference.baseCents();
                String activation;

                if (!changed) {
                    activation =
                            reference.activationState();
                } else if (draft.recipeCapped) {
                    activation =
                            "V10_LIVE_RECIPE_SAFE";
                } else if (draft.centFeasibilityAdjusted
                        || draft.structuralFloorAdjusted) {
                    activation =
                            "V10_LIVE_FLOOR";
                } else {
                    activation =
                            "V10_LIVE";
                }

                entries.put(
                        material,
                        new SellCatalogEntry(
                                material,
                                base,
                                reference.category(),
                                reference
                                        .serverSellEnabled(),
                                false,
                                normalizeMarketKey(
                                        reference
                                                .marketKey()
                                ),
                                Math.max(
                                        1L,
                                        reference
                                                .marketUnits()
                                ),
                                1L,
                                1.0D,
                                1.0D,
                                reference
                                        .buybackMultiplier(),
                                reference
                                        .enchantBuybackMultiplier(),
                                reference
                                        .priceSource(),
                                reference
                                        .autoSellApproved(),
                                activation,
                                reference
                                        .operatorLocked(),
                                SellPricingPolicy
                                        .CATALOG_REVISION
                        )
                );
            }

            return new SellCatalogSnapshot(
                    SellPricingPolicy
                            .CATALOG_REVISION,
                    entries.size(),
                    System.currentTimeMillis(),
                    Map.copyOf(entries)
            );
        }

        private Map<String, Double>
        effectiveMultipliers(
                Map<String, Double> requested,
                SellCatalogSnapshot liveSnapshot
        ) {
            if (requested.isEmpty()) {
                return Map.of();
            }

            Map<String, BigDecimal> liveUnits =
                    SellCatalogV10Compiler.this.referenceUnitCents(
                            liveSnapshot
                    );
            Map<String, Double> result =
                    new LinkedHashMap<>();

            for (String key : requested.keySet()) {
                BigDecimal reference =
                        referenceUnitCents.get(key);
                BigDecimal live =
                        liveUnits.get(key);

                if (reference == null
                        || live == null
                        || reference.signum() <= 0
                        || live.signum() <= 0) {
                    continue;
                }

                try {
                    double value =
                            live.divide(
                                            reference,
                                            8,
                                            RoundingMode.HALF_UP
                                    )
                                    .doubleValue();

                    if (Double.isFinite(value)
                            && value > 0.0D) {
                        result.put(
                                key,
                                value
                        );
                    }
                } catch (ArithmeticException ignored) {
                }
            }

            return Map.copyOf(result);
        }
    }

    private Map<Material, Draft>
    draftsFromReference(
            SellCatalogSnapshot reference
    ) {
        Map<Material, Draft> drafts =
                new EnumMap<>(
                        Material.class
                );

        for (SellCatalogEntry entry
                : reference.entries().values()) {
            PriceSource source =
                    parsePriceSource(
                            entry.priceSource()
                    );
            Draft draft =
                    new Draft(
                            entry.material(),
                            normalizeCategory(
                                    entry.category()
                            ),
                            Math.max(
                                    SellPricingPolicy
                                            .MINIMUM_UNIT_CENTS,
                                    entry.baseCents()
                            ),
                            source
                                    == PriceSource.CURATED,
                            source
                    );
            draft.variant =
                    source
                            == PriceSource
                            .VARIANT_REQUIRED;
            draft.marketKey =
                    normalizeMarketKey(
                            entry.marketKey()
                    );
            draft.marketUnits =
                    Math.max(
                            1L,
                            entry.marketUnits()
                    );
            drafts.put(
                    entry.material(),
                    draft
            );
        }

        return drafts;
    }

    private PriceSource parsePriceSource(
            String raw
    ) {
        if (raw == null
                || raw.isBlank()) {
            return PriceSource
                    .GENERATED_CATEGORY;
        }

        try {
            return PriceSource.valueOf(
                    raw.trim()
                            .toUpperCase(
                                    Locale.ROOT
                            )
            );
        } catch (IllegalArgumentException exception) {
            return PriceSource
                    .GENERATED_CATEGORY;
        }
    }

    private CommodityBuild commodityBuildFromReference(
            SellCatalogSnapshot reference,
            List<RecipeSeed> recipes
    ) {
        Map<String, List<SellCatalogEntry>> groups =
                new LinkedHashMap<>();

        for (SellCatalogEntry entry
                : reference.entries().values()) {
            groups.computeIfAbsent(
                    normalizeMarketKey(
                            entry.marketKey()
                    ),
                    ignored ->
                            new ArrayList<>()
            ).add(entry);
        }

        Map<Material, CommodityInfo> info =
                new EnumMap<>(
                        Material.class
                );
        int groupCount = 0;

        for (Map.Entry<String, List<SellCatalogEntry>>
                group : groups.entrySet()) {
            boolean commodity =
                    group.getValue().size() > 1
                            || group.getValue()
                            .stream()
                            .anyMatch(
                                    entry ->
                                            entry.marketUnits()
                                                    != 1L
                            );

            if (!commodity) {
                continue;
            }

            groupCount++;

            for (SellCatalogEntry entry
                    : group.getValue()) {
                info.put(
                        entry.material(),
                        new CommodityInfo(
                                group.getKey(),
                                Math.max(
                                        1L,
                                        entry.marketUnits()
                                )
                        )
                );
            }
        }

        Set<Integer> equivalent =
                new HashSet<>();

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

            CommodityInfo input =
                    info.get(
                            conversion.input()
                    );
            CommodityInfo output =
                    info.get(
                            conversion.output()
                    );

            if (input == null
                    || output == null
                    || !input.marketKey()
                    .equals(
                            output.marketKey()
                    )) {
                continue;
            }

            long inputUnits =
                    safeMultiply(
                            conversion.inputAmount(),
                            input.marketUnits()
                    );
            long outputUnits =
                    safeMultiply(
                            conversion.outputAmount(),
                            output.marketUnits()
                    );

            if (inputUnits > 0L
                    && inputUnits
                    != Long.MAX_VALUE
                    && inputUnits
                    == outputUnits) {
                equivalent.add(index);
            }
        }

        return new CommodityBuild(
                Map.copyOf(info),
                Set.copyOf(equivalent),
                groupCount
        );
    }

    private Map<String, BigDecimal>
    referenceUnitCents(
            SellCatalogSnapshot snapshot
    ) {
        Map<String, BigDecimal> result =
                new LinkedHashMap<>();

        for (SellCatalogEntry entry
                : snapshot.entries().values()) {
            if (entry.baseCents() <= 0L
                    || entry.marketUnits() <= 0L) {
                continue;
            }

            String key =
                    normalizeMarketKey(
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
            BigDecimal current =
                    result.get(key);

            if (current == null
                    || unit.compareTo(current) < 0) {
                result.put(
                        key,
                        unit
                );
            }
        }

        return Map.copyOf(result);
    }

    private String normalizeMarketKey(
            String raw
    ) {
        if (raw == null
                || raw.isBlank()) {
            return "";
        }

        return raw.trim()
                .toUpperCase(
                        Locale.ROOT
                )
                .replace('-', '_')
                .replace(' ', '_');
    }

    public record LiveCompilation(
            SellCatalogSnapshot snapshot,
            Map<String, Double> effectiveMultipliers,
            List<String> failures
    ) {
        public LiveCompilation {
            effectiveMultipliers =
                    effectiveMultipliers == null
                            ? Map.of()
                            : Map.copyOf(
                            effectiveMultipliers
                    );
            failures =
                    failures == null
                            ? List.of()
                            : List.copyOf(failures);
        }

        public boolean ready() {
            return snapshot != null
                    && failures.isEmpty();
        }
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
        private final Material material;
        private final String category;
        private long baseCents;
        private final boolean explicit;
        private PriceSource source;
        private boolean recipeCapped;
        private boolean variant;
        private boolean derivedCandidate;
        private boolean fallbackAfterDerivation;
        private boolean structuralFloorAdjusted;
        private boolean centFeasibilityAdjusted;
        private long minimumBaseCents =
                SellPricingPolicy.MINIMUM_UNIT_CENTS;
        private String marketKey;
        private long marketUnits = 1L;

        private Draft(
                Material material,
                String category,
                long baseCents,
                boolean explicit,
                PriceSource source
        ) {
            this.material = material;
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
