package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class SellMultiGui {

    public static final String TITLE = "Market Overview";

    private static final int[] CATEGORY_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17
    };

    private SellMultiGui() {
    }

    public static void open(
            Core core,
            Player player,
            SellService sellService
    ) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                TITLE
        );
        holder.inventory = inventory;

        List<String> categories =
                sellService.multiplierCategories();

        for (int index = 0;
             index < categories.size()
                     && index < CATEGORY_SLOTS.length;
             index++) {
            String category = categories.get(index);

            inventory.setItem(
                    CATEGORY_SLOTS[index],
                    categoryItem(sellService, category)
            );
        }

        player.openInventory(inventory);
    }

    public static boolean isInventory(Inventory inventory) {
        return inventory != null
                && inventory.getHolder(false) instanceof Holder;
    }

    public static boolean isTitle(String strippedTitle) {
        return strippedTitle != null
                && strippedTitle.equalsIgnoreCase(TITLE);
    }

    private static ItemStack categoryItem(
            SellService sellService,
            String category
    ) {
        double multiplier =
                sellService.categoryMarketMultiplier(category);
        long sold =
                sellService.categoryRollingAmount(category);
        long target =
                sellService.categoryTargetUnits(category);
        int featured =
                sellService.categoryFeaturedItems(category);

        return item(
                categoryMaterial(category),
                "&d" + sellService.categoryDisplay(category),
                List.of(
                        "&#bbbbbbMarket: &#ff88ff"
                                + SellService.formatMultiplier(
                                multiplier
                        )
                                + "x",
                        "&#bbbbbb24h Supply: &#ff88ff"
                                + MoneyFormatter.compact(sold),
                        "&#bbbbbbDaily Target: &#ff88ff"
                                + MoneyFormatter.compact(target),
                        "&#bbbbbbFeatured Items: &#ff88ff"
                                + featured
                )
        );
    }

    private static Material categoryMaterial(String category) {
        return switch (category.toLowerCase()) {
            case "blocks" -> Material.GRASS_BLOCK;
            case "ores" -> Material.DIAMOND;
            case "wood" -> Material.OAK_LOG;
            case "farming" -> Material.WHEAT;
            case "mob_drops" -> Material.BONE;
            case "nether" -> Material.NETHERRACK;
            case "end" -> Material.END_STONE;
            case "combat" -> Material.DIAMOND_SWORD;
            default -> Material.CHEST;
        };
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(TextColor.color(name));
        meta.setLore(
                lore.stream().map(TextColor::color).toList()
        );
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
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
