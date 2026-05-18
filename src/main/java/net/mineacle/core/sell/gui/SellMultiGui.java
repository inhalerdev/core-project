package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class SellMultiGui {

    public static final String TITLE = "Sell Multipliers";

    private static final int[] CATEGORY_SLOTS = {10, 11, 12, 13, 14, 15, 16, 22};

    private SellMultiGui() {
    }

    public static void open(Core core, Player player, SellService sellService) {
        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);

        List<String> categories = sellService.multiplierCategories();

        for (int index = 0; index < categories.size() && index < CATEGORY_SLOTS.length; index++) {
            String category = categories.get(index);
            inventory.setItem(CATEGORY_SLOTS[index], categoryItem(player, sellService, category));
        }

        player.openInventory(inventory);
    }

    public static boolean isTitle(String strippedTitle) {
        return strippedTitle != null && strippedTitle.equalsIgnoreCase(TITLE);
    }

    private static ItemStack categoryItem(Player player, SellService sellService, String category) {
        double multiplier = sellService.multiplier(player.getUniqueId(), category);
        double increase = sellService.categoryIncreasePerLevel(category);
        double max = sellService.categoryMaxMultiplier(category);

        long sold = sellService.categorySoldAmount(player.getUniqueId(), category);
        long progress = sellService.categoryProgressAmount(player.getUniqueId(), category);
        long needed = sellService.categoryAmountPerLevel(category);
        long remaining = sellService.categoryRemainingAmount(player.getUniqueId(), category);

        return item(
                categoryMaterial(category),
                "&d" + sellService.categoryDisplay(category),
                List.of(
                        "&#bbbbbbMultiplier: &#ff88ff" + SellService.formatMultiplier(multiplier) + "x",
                        "&#bbbbbbSold: &#ff88ff" + MoneyFormatter.compact(sold),
                        "&#bbbbbbProgress: &#ff88ff" + MoneyFormatter.compact(progress) + "&#bbbbbb/&#ff88ff" + MoneyFormatter.compact(needed),
                        "&#bbbbbbRemaining: &#ff88ff" + MoneyFormatter.compact(remaining),
                        "&#bbbbbbNext Upgrade: &#ff88ff+" + SellService.formatMultiplier(increase) + "x",
                        "&#bbbbbbMax Multiplier: &#ff88ff" + SellService.formatMultiplier(max) + "x"
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
            default -> Material.CHEST;
        };
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(TextColor.color(name));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
