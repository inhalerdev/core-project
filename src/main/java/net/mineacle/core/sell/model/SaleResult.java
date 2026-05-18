package net.mineacle.core.sell.model;

import org.bukkit.inventory.ItemStack;

import java.util.List;

public record SaleResult(
        boolean soldAnything,
        long totalCents,
        long totalAmount,
        List<ItemStack> returnedItems
) {
}
