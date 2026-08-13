package net.mineacle.core.sell.model;

import org.bukkit.Material;

public record SellCatalogEntry(
        Material material,
        long baseCents,
        String category,
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
