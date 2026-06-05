package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class SellGui {

    public static final int SIZE = 36;
    public static final int SUMMARY_SLOT = 35;

    private SellGui() {
    }

    public static void open(Core core, Player player, SellService sellService) {
        Inventory inventory = Bukkit.createInventory(null, SIZE, title(core));
        updateSummary(player, inventory, sellService);
        player.openInventory(inventory);
    }

    public static void open(Core core, Player player) {
        Inventory inventory = Bukkit.createInventory(null, SIZE, title(core));
        player.openInventory(inventory);
    }

    public static String title(Core core) {
        return TextColor.color(core.getConfig().getString("sell.gui.title", "Place Items In Here To Sell"));
    }

    public static void updateSummary(Player player, Inventory inventory, SellService sellService) {
        if (player == null || inventory == null || sellService == null || inventory.getSize() <= SUMMARY_SLOT) {
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

            ItemStack clean = sellService.stripWorthLore(item);

            if (!sellService.canSell(clean)) {
                ignoredAmount += clean.getAmount();
                continue;
            }

            totalCents += sellService.stackWorthCents(player, clean);
            totalAmount += clean.getAmount();
        }

        inventory.setItem(SUMMARY_SLOT, summaryItem(sellService, totalCents, totalAmount, ignoredAmount));
    }

    private static ItemStack summaryItem(SellService sellService, long totalCents, long totalAmount, long ignoredAmount) {
        ItemStack item = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<String> lore = new ArrayList<>();
        lore.add("&#bbbbbbValue: &a" + sellService.format(totalCents));
        lore.add("&#bbbbbbItems: &#ff88ff" + totalAmount);

        if (ignoredAmount > 0L) {
            lore.add("&#bbbbbbIgnored: &c" + ignoredAmount);
        }

        lore.add("");
        lore.add("&#bbbbbbClose this menu to sell");
        lore.add("&#bbbbbbUnsold items are returned");

        meta.setDisplayName(TextColor.color("&aSell Summary"));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        item.setItemMeta(meta);
        return item;
    }
}
