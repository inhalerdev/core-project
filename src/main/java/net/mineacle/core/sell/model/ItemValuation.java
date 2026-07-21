package net.mineacle.core.sell.model;

import org.bukkit.Material;

public record ItemValuation(
        Material material,
        int amount,
        boolean priced,
        boolean explicitlyPriced,
        boolean sellable,
        String sellReason,
        String category,
        long baseUnitCents,
        double marketMultiplier,
        double featuredMultiplier,
        double combinedMarketMultiplier,
        int durabilityPercent,
        boolean mending,
        double baseDurabilityMultiplier,
        double enchantDurabilityMultiplier,
        long appraisedBaseCents,
        long appraisedEnchantCents,
        long appraisedTotalCents,
        double baseBuybackMultiplier,
        double enchantBuybackMultiplier,
        long serverBaseCents,
        long serverEnchantCents,
        long serverSellCents
) {

    public static ItemValuation unpriced(
            Material material,
            int amount,
            String reason
    ) {
        return new ItemValuation(
                material == null ? Material.AIR : material,
                Math.max(1, amount),
                false,
                false,
                false,
                reason == null ? "no-price" : reason,
                "misc",
                0L,
                1.0D,
                1.0D,
                1.0D,
                100,
                false,
                1.0D,
                1.0D,
                0L,
                0L,
                0L,
                0.0D,
                0.0D,
                0L,
                0L,
                0L
        );
    }

    public boolean damaged() {
        return durabilityPercent < 100;
    }

    public boolean playerMarketOnly() {
        return priced && !sellable;
    }
}
