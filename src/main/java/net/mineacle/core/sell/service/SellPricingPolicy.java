package net.mineacle.core.sell.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic bootstrap policy for Sell/Worth catalog revision 10.
 *
 * <p>Revision 10 separates the server's static reference catalog from live
 * market learning. The bootstrap catalog is conservative and recipe-safe;
 * adaptive movement is intentionally disabled here until the v10 learner has
 * enough durable evidence to publish a later live market snapshot.</p>
 *
 * <p>Configured item prices are reference values, not hidden appraisal values.
 * Revision 10 therefore does not apply category liquidation haircuts or
 * category price caps to an explicitly configured reference.</p>
 */
public final class SellPricingPolicy {

    public static final int CATALOG_REVISION = 10;
    public static final long MINIMUM_UNIT_CENTS = 1L;
    public static final double ONE_WAY_RECIPE_RETENTION = 0.82D;

    private static final Map<String, CategoryPolicy> POLICIES =
            Map.ofEntries(
                    Map.entry("blocks", new CategoryPolicy(10L, 100L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("ores", new CategoryPolicy(50L, 500L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("wood", new CategoryPolicy(25L, 200L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("farming", new CategoryPolicy(10L, 100L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("mob_drops", new CategoryPolicy(25L, 300L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("nether", new CategoryPolicy(50L, 500L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("end", new CategoryPolicy(100L, 1_000L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("combat", new CategoryPolicy(50L, 500L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("equipment", new CategoryPolicy(100L, 1_000L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("consumables", new CategoryPolicy(50L, 500L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("utility", new CategoryPolicy(100L, 1_500L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("rare", new CategoryPolicy(500L, 10_000L, false, 1.0D, 1.0D, 1.0D)),
                    Map.entry("misc", new CategoryPolicy(25L, 300L, false, 1.0D, 1.0D, 1.0D))
            );

    private static final CategoryPolicy DEFAULT_POLICY =
            new CategoryPolicy(25L, 300L, false, 1.0D, 1.0D, 1.0D);

    private SellPricingPolicy() {
    }

    /**
     * Returns the bootstrap reference for an unconfigured material.
     *
     * <p>The configured category fallback is treated as an actual reference,
     * not silently capped. Craftable outputs are subsequently constrained by
     * the forward recipe compiler.</p>
     */
    public static long automaticSeedCents(
            String category,
            long configuredFallbackCents
    ) {
        if (configuredFallbackCents > 0L) {
            return configuredFallbackCents;
        }

        return categoryPolicy(category).defaultSeedCents();
    }

    /**
     * Revision 10 treats an explicitly configured price as an exact reference.
     * Recipe safety may later cap a craftable output, but there is no hidden
     * category liquidation discount here.
     */
    public static long curatedLiquidationCents(
            String category,
            long configuredBaseCents
    ) {
        if (configuredBaseCents <= 0L) {
            return 0L;
        }

        CategoryPolicy policy =
                categoryPolicy(category);

        try {
            return BigDecimal.valueOf(
                    configuredBaseCents
            )
                    .multiply(
                            BigDecimal.valueOf(
                                    policy.curatedLiquidationFactor()
                            )
                    )
                    .setScale(
                            0,
                            RoundingMode.DOWN
                    )
                    .max(BigDecimal.ZERO)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * The legacy v9 demand engine must not move a revision-10 bootstrap row.
     * Live movement is reserved for the v10 learning authority.
     */
    public static boolean automaticMarketEnabled(
            String category
    ) {
        return categoryPolicy(
                category
        ).dynamicMarket();
    }

    /**
     * Revision-10 bootstrap rows are static. This method remains for source
     * compatibility with the retired v9 bootstrap class.
     */
    public static MarketBounds marketBounds(
            String category,
            double configuredMinimum,
            double configuredMaximum
    ) {
        CategoryPolicy policy =
                categoryPolicy(category);

        if (!policy.dynamicMarket()) {
            return MarketBounds.STATIC;
        }

        double minimum =
                Math.clamp(
                        finitePositive(
                                configuredMinimum,
                                policy.defaultMinimumMultiplier()
                        ),
                        policy.defaultMinimumMultiplier(),
                        1.0D
                );
        double maximum =
                Math.clamp(
                        finitePositive(
                                configuredMaximum,
                                policy.maximumMultiplier()
                        ),
                        1.0D,
                        policy.maximumMultiplier()
                );

        return new MarketBounds(
                minimum,
                Math.max(
                        minimum,
                        maximum
                )
        );
    }

    public static long outputBaseCeilingCents(
            long ingredientPayoutCents,
            int outputAmount,
            double outputMaximumMultiplier
    ) {
        return outputBaseCeilingCents(
                ingredientPayoutCents,
                outputAmount,
                outputMaximumMultiplier,
                ONE_WAY_RECIPE_RETENTION
        );
    }

    /**
     * Maximum safe unit value for a recipe output.
     *
     * <p>The caller supplies the retention. Normal one-way recipes use 82%.
     * Non-commodity recipe cycles use 100% because applying a strict haircut
     * around every edge of a positive cycle has no non-zero fixed point. A
     * 100% cyclic boundary still guarantees that crafting cannot create money.</p>
     */
    public static long outputBaseCeilingCents(
            long ingredientPayoutCents,
            int outputAmount,
            double outputMaximumMultiplier,
            double retention
    ) {
        if (ingredientPayoutCents <= 0L
                || outputAmount <= 0
                || !Double.isFinite(outputMaximumMultiplier)
                || outputMaximumMultiplier <= 0.0D
                || !Double.isFinite(retention)
                || retention <= 0.0D
                || retention > 1.0D) {
            return 0L;
        }

        try {
            return BigDecimal.valueOf(ingredientPayoutCents)
                    .multiply(BigDecimal.valueOf(retention))
                    .divide(
                            BigDecimal.valueOf(outputAmount)
                                    .multiply(
                                            BigDecimal.valueOf(
                                                    outputMaximumMultiplier
                                            )
                                    ),
                            0,
                            RoundingMode.DOWN
                    )
                    .max(BigDecimal.ZERO)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    public static CategoryPolicy categoryPolicy(String category) {
        return POLICIES.getOrDefault(
                normalizeCategory(category),
                DEFAULT_POLICY
        );
    }

    private static double finitePositive(
            double value,
            double fallback
    ) {
        return Double.isFinite(value)
                && value > 0.0D
                ? value
                : fallback;
    }

    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "misc";
        }

        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    /**
     * Kept source-compatible with revision 9. Revision 10 intentionally sets
     * dynamicMarket=false and curatedLiquidationFactor=1 for every category.
     */
    public record CategoryPolicy(
            long defaultSeedCents,
            long maximumAutomaticSeedCents,
            boolean dynamicMarket,
            double defaultMinimumMultiplier,
            double maximumMultiplier,
            double curatedLiquidationFactor
    ) {
        public CategoryPolicy {
            defaultSeedCents = Math.max(
                    MINIMUM_UNIT_CENTS,
                    defaultSeedCents
            );
            maximumAutomaticSeedCents = Math.max(
                    defaultSeedCents,
                    maximumAutomaticSeedCents
            );

            if (!dynamicMarket) {
                defaultMinimumMultiplier = 1.0D;
                maximumMultiplier = 1.0D;
            } else {
                defaultMinimumMultiplier = Math.clamp(
                        finitePositive(
                                defaultMinimumMultiplier,
                                0.5D
                        ),
                        0.05D,
                        1.0D
                );
                maximumMultiplier = Math.max(
                        1.0D,
                        finitePositive(
                                maximumMultiplier,
                                1.0D
                        )
                );
            }

            curatedLiquidationFactor = 1.0D;
        }
    }

    public record MarketBounds(
            double minimumMultiplier,
            double maximumMultiplier
    ) {
        private static final MarketBounds STATIC =
                new MarketBounds(1.0D, 1.0D);

        public MarketBounds {
            minimumMultiplier = finitePositive(
                    minimumMultiplier,
                    1.0D
            );
            maximumMultiplier = Math.max(
                    minimumMultiplier,
                    finitePositive(
                            maximumMultiplier,
                            minimumMultiplier
                    )
            );
        }
    }
}
