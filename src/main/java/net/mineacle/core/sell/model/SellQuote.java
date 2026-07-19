package net.mineacle.core.sell.model;

import org.bukkit.Material;

public record SellQuote(
        boolean sellable,
        String reason,
        Material material,
        int amount,
        long baseUnitCents,
        double marketMultiplier,
        double featuredMultiplier,
        int durabilityPercent,
        double baseDurabilityMultiplier,
        double enchantDurabilityMultiplier,
        long adjustedBaseCents,
        long adjustedEnchantCents,
        long totalCents
) {

    public static SellQuote unsellable(
            Material material,
            int amount,
            String reason
    ) {
        return new SellQuote(
                false,
                reason == null ? "" : reason,
                material,
                Math.max(1, amount),
                0L,
                1.0D,
                1.0D,
                100,
                1.0D,
                1.0D,
                0L,
                0L,
                0L
        );
    }

    public boolean damaged() {
        return durabilityPercent < 100;
    }

    public double combinedMarketMultiplier() {
        return marketMultiplier * featuredMultiplier;
    }
}
