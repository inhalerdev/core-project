package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.model.SellQuote;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class SellGui {

    public static final int SIZE = 36;
    public static final int SUMMARY_SLOT =
            CenteredToolbar.centerSlot(SIZE);

    private SellGui() {
    }

    public static void open(
            Core core,
            Player player,
            SellService sellService
    ) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                title(core)
        );
        holder.inventory = inventory;

        updateSummary(player, inventory, sellService);
        player.openInventory(inventory);
    }

    public static void open(Core core, Player player) {
        SellService sellService =
                net.mineacle.core.sell.SellModule.sellService();

        if (sellService != null) {
            open(core, player, sellService);
        }
    }

    public static boolean isInventory(Inventory inventory) {
        return inventory != null
                && inventory.getHolder(false) instanceof Holder;
    }

    public static String title(Core core) {
        return TextColor.color(
                core.getConfig().getString(
                        "sell.gui.title",
                        "Place Items In Here To Sell"
                )
        );
    }

    public static void updateSummary(
            Player player,
            Inventory inventory,
            SellService sellService
    ) {
        if (player == null
                || inventory == null
                || sellService == null
                || inventory.getSize() <= SUMMARY_SLOT) {
            return;
        }

        long totalCents = 0L;
        long totalAmount = 0L;
        long ignoredAmount = 0L;

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot == SUMMARY_SLOT) {
                continue;
            }

            ItemStack item = inventory.getItem(slot);

            if (item == null || item.getType().isAir()) {
                continue;
            }

            SellQuote quote = sellService.quote(
                    player.getUniqueId(),
                    item
            );

            if (!quote.sellable()) {
                ignoredAmount = safeAdd(
                        ignoredAmount,
                        item.getAmount()
                );
                continue;
            }

            totalCents = safeAdd(
                    totalCents,
                    quote.totalCents()
            );
            totalAmount = safeAdd(
                    totalAmount,
                    item.getAmount()
            );
        }

        inventory.setItem(
                SUMMARY_SLOT,
                summaryItem(
                        sellService,
                        totalCents,
                        totalAmount,
                        ignoredAmount
                )
        );
    }

    private static ItemStack summaryItem(
            SellService sellService,
            long totalCents,
            long totalAmount,
            long ignoredAmount
    ) {
        ItemStack item = new ItemStack(
                Material.GREEN_STAINED_GLASS_PANE
        );
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbPending Payout: &a"
                        + sellService.format(totalCents)
        );
        lore.add(
                "&#bbbbbbItems: &#ff88ff" + totalAmount
        );

        if (ignoredAmount > 0L) {
            lore.add(
                    "&#bbbbbbIgnored: &c" + ignoredAmount
            );
        }

        lore.add("");
        lore.add("&#bbbbbbClose this menu to sell");
        lore.add("&#bbbbbbUnsold items are returned");

        meta.setDisplayName(TextColor.color("&aSell Summary"));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static long safeAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static final class Holder
            implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
