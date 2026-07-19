package net.mineacle.core.sell.model;

import org.bukkit.inventory.ItemStack;

import java.util.List;

public record SaleResult(
        boolean soldAnything,
        long totalCents,
        long totalAmount,
        List<ItemStack> returnedItems,
        String failureMessage
) {

    public SaleResult(
            boolean soldAnything,
            long totalCents,
            long totalAmount,
            List<ItemStack> returnedItems
    ) {
        this(
                soldAnything,
                totalCents,
                totalAmount,
                returnedItems,
                ""
        );
    }
}
