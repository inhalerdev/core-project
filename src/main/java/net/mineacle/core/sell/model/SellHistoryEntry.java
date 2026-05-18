package net.mineacle.core.sell.model;

import org.bukkit.Material;

public record SellHistoryEntry(
        Material material,
        long amount,
        long totalCents,
        long lastSoldMillis
) {
}
