package net.mineacle.core.sell.model;

import org.bukkit.Material;

import java.util.Map;

public record SellCatalogSnapshot(
        int revision,
        int expectedRows,
        long loadedAt,
        Map<Material, SellCatalogEntry> entries
) {

    public SellCatalogSnapshot {
        entries = entries == null
                ? Map.of()
                : Map.copyOf(entries);
    }

    public int sellEnabledRows() {
        int total = 0;

        for (SellCatalogEntry entry : entries.values()) {
            if (entry.serverSellEnabled()) {
                total++;
            }
        }

        return total;
    }

    @SuppressWarnings("unused")
    public int reviewRows() {
        return Math.max(
                0,
                entries.size() - sellEnabledRows()
        );
    }

}
