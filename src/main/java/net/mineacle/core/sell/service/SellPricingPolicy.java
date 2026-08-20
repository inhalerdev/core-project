package net.mineacle.core.sell.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic economy policy for Sell/Worth catalog revision 9.
 *
 * <p>Worth is the server's guaranteed liquidation value.  The catalog may
 * move selected primary commodities with demand, but every generated price is
 * constructed against the cheapest valid crafting path and the worst allowed
 * market state so crafting cannot manufacture server cash.</p>
 */
public final class SellPricingPolicy {

    public static final int CATALOG_REVISION = 9;
    public static final long MINIMUM_UNIT_CENTS = 1L;
    public static final double ONE_WAY_RECIPE_RETENTION = 0.82D;

    private static final Map<String, CategoryPolicy> POLICIES =
            Map.ofEntries(
                    Map.entry("blocks", new CategoryPolicy(10L, 100L, true, 0.55D, 1.35D, 1.00D)),
                    Map.entry("ores", new CategoryPolicy(50L, 500L, true, 0.55D, 1.40D, 1.00D)),
                    Map.entry("wood", new CategoryPolicy(25L, 200L, true, 0.55D, 1.35D, 1.00D)),
                    Map.entry("farming", new CategoryPolicy(10L, 100L, true, 0.35D, 1.45D, 1.00D)),
                    Map.entry("mob_drops", new CategoryPolicy(25L, 300L, true, 0.40D, 1.45D, 1.00D)),
                    Map.entry("nether", new CategoryPolicy(50L, 500L, true, 0.50D, 1.40D, 0.95D)),
                    Map.entry("end", new CategoryPolicy(100L, 1_000L, true, 0.60D, 1.35D, 0.90D)),
                    Map.entry("combat", new CategoryPolicy(50L, 500L, false, 1.00D, 1.00D, 0.45D)),
                    Map.entry("equipment", new CategoryPolicy(100L, 1_000L, false, 1.00D, 1.00D, 0.30D)),
                    Map.entry("consumables", new CategoryPolicy(50L, 500L, false, 1.00D, 1.00D, 0.75D)),
                    Map.entry("utility", new CategoryPolicy(100L, 1_500L, false, 1.00D, 1.00D, 0.45D)),
                    Map.entry("rare", new CategoryPolicy(500L, 10_000L, false, 1.00D, 1.00D, 0.20D)),
                    Map.entry("misc", new CategoryPolicy(25L, 300L, false, 1.00D, 1.00D, 0.50D))
            );

    private static final CategoryPolicy DEFAULT_POLICY =
            new CategoryPolicy(25L, 300L, false, 1.00D, 1.00D, 0.50D);

    private SellPricingPolicy() {
    }

    /**
     * Safe automatic value for a material without a curated anchor.
     * Legacy category fallbacks are treated as hints and capped so a future
     * Minecraft material cannot inherit an unexpectedly huge payout.
     */
    public static long automaticSeedCents(
            String category,
            long configuredFallbackCents
    ) {
        CategoryPolicy policy = categoryPolicy(category);
        long configured = Math.max(0L, configuredFallbackCents);

        if (configured <= 0L) {
            return policy.defaultSeedCents();
        }

        return Math.clamp(
                configured,
                MINIMUM_UNIT_CENTS,
                policy.maximumAutomaticSeedCents()
        );
    }

    /**
     * Converts the old appraisal-oriented curated price into a liquidation
     * anchor.  Resource categories remain 1:1 while gear/utility/rare values
     * are intentionally discounted before recipe ceilings are applied.
     */
    public static long curatedLiquidationCents(
            String category,
            long configuredBaseCents
    ) {
        if (configuredBaseCents <= 0L) {
            return 0L;
        }

        long liquidation = Math.max(
                MINIMUM_UNIT_CENTS,
                multiplyDown(
                        configuredBaseCents,
                        categoryPolicy(category).curatedLiquidationFactor()
                )
        );

        return Math.min(
                liquidation,
                maximumCuratedLiquidationCents(category)
        );
    }

    private static long maximumCuratedLiquidationCents(String category) {
        return switch (normalizeCategory(category)) {
            case "rare" -> 10_000_000L;       // $100,000
            case "equipment" -> 5_000_000L;  // $50,000
            case "utility" -> 2_000_000L;    // $20,000
            case "combat" -> 1_000_000L;     // $10,000
            case "consumables" -> 500_000L;  // $5,000
            case "misc" -> 100_000L;         // $1,000
            default -> 100_000_000L;          // resource anchors: $1,000,000
        };
    }

    public static boolean automaticMarketEnabled(String category) {
        return categoryPolicy(category).dynamicMarket();
    }

    /**
     * Narrows legacy market bounds rather than widening them.  Primary
     * commodities still move aggressively enough to react to farms and
     * shortages, but low-population noise cannot collapse a normal value to
     * 0.15x or spike it to 1.75x.
     */
    public static MarketBounds marketBounds(
            String category,
            double configuredMinimum,
            double configuredMaximum
    ) {
        CategoryPolicy policy = categoryPolicy(category);

        if (!policy.dynamicMarket()) {
            return MarketBounds.STATIC;
        }

        double minimum = finitePositive(
                configuredMinimum,
                policy.defaultMinimumMultiplier()
        );
        minimum = Math.clamp(
                minimum,
                policy.defaultMinimumMultiplier(),
                1.0D
        );

        double maximum = finitePositive(
                configuredMaximum,
                policy.maximumMultiplier()
        );
        maximum = Math.clamp(
                maximum,
                1.0D,
                policy.maximumMultiplier()
        );

        return new MarketBounds(minimum, Math.max(minimum, maximum));
    }

    /**
     * Base-price ceiling for a static or dynamic output when ingredients are
     * evaluated at their minimum reachable server payout.
     */
    public static long outputBaseCeilingCents(
            long ingredientMinimumPayoutCents,
            int outputAmount,
            double outputMaximumMultiplier
    ) {
        if (ingredientMinimumPayoutCents <= 0L
                || outputAmount <= 0
                || !Double.isFinite(outputMaximumMultiplier)
                || outputMaximumMultiplier <= 0.0D) {
            return 0L;
        }

        try {
            return BigDecimal.valueOf(ingredientMinimumPayoutCents)
                    .multiply(BigDecimal.valueOf(ONE_WAY_RECIPE_RETENTION))
                    .divide(
                            BigDecimal.valueOf(outputAmount)
                                    .multiply(BigDecimal.valueOf(outputMaximumMultiplier)),
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
        return POLICIES.getOrDefault(normalizeCategory(category), DEFAULT_POLICY);
    }

    private static long multiplyDown(long cents, double multiplier) {
        try {
            return BigDecimal.valueOf(cents)
                    .multiply(BigDecimal.valueOf(multiplier))
                    .setScale(0, RoundingMode.DOWN)
                    .max(BigDecimal.ZERO)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static double finitePositive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D
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

    public record CategoryPolicy(
            long defaultSeedCents,
            long maximumAutomaticSeedCents,
            boolean dynamicMarket,
            double defaultMinimumMultiplier,
            double maximumMultiplier,
            double curatedLiquidationFactor
    ) {
        public CategoryPolicy {
            defaultSeedCents = Math.max(MINIMUM_UNIT_CENTS, defaultSeedCents);
            maximumAutomaticSeedCents = Math.max(
                    defaultSeedCents,
                    maximumAutomaticSeedCents
            );
            curatedLiquidationFactor = Math.clamp(
                    finitePositive(curatedLiquidationFactor, 1.0D),
                    0.01D,
                    1.0D
            );

            if (!dynamicMarket) {
                defaultMinimumMultiplier = 1.0D;
                maximumMultiplier = 1.0D;
            } else {
                defaultMinimumMultiplier = Math.clamp(
                        finitePositive(defaultMinimumMultiplier, 0.5D),
                        0.05D,
                        1.0D
                );
                maximumMultiplier = Math.max(
                        1.0D,
                        finitePositive(maximumMultiplier, 1.0D)
                );
            }
        }
    }

    public record MarketBounds(
            double minimumMultiplier,
            double maximumMultiplier
    ) {
        private static final MarketBounds STATIC = new MarketBounds(1.0D, 1.0D);

        public MarketBounds {
            minimumMultiplier = finitePositive(minimumMultiplier, 1.0D);
            maximumMultiplier = Math.max(
                    minimumMultiplier,
                    finitePositive(maximumMultiplier, minimumMultiplier)
            );
        }
    }
}
