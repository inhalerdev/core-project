package net.mineacle.core.sell.model;

import org.bukkit.Material;

public record MarketDefinition(
        Material material,
        long baseCents,
        String category,
        boolean marketEnabled,
        long targetUnitsPerDay,
        double minimumMultiplier,
        double maximumMultiplier
) {
}
