package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
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

public final class SellMetaGui {

    public static final String TITLE = "META Sell Items";

    private SellMetaGui() {
    }

    public static void open(Core core, Player player, SellService sellService) {
        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);

        List<Material> items = sellService.activeMetaItems();

        if (items.isEmpty()) {
            inventory.setItem(13, item(
                    Material.CHEST,
                    "&dNo META Items",
                    List.of(
                            "&#bbbbbbMETA sell boosts are not active",
                            "&#bbbbbbCheck back later"
                    )
            ));

            player.openInventory(inventory);
            return;
        }

        int slot = 10;

        for (Material material : items) {
            if (slot > 16) {
                break;
            }

            inventory.setItem(slot, metaItem(player, sellService, material));
            slot++;
        }

        inventory.setItem(22, item(
                Material.CLOCK,
                "&dNext Rotation",
                List.of(
                        "&#bbbbbbResets in &#ff6fff" + sellService.metaTimeRemaining(),
                        "&#bbbbbbCurrent boosted items will change"
                )
        ));

        player.openInventory(inventory);
    }

    public static boolean isTitle(String strippedTitle) {
        return strippedTitle != null && strippedTitle.equalsIgnoreCase(TITLE);
    }

    private static ItemStack metaItem(Player player, SellService sellService, Material material) {
        double multiplier = sellService.metaMultiplier(material);
        long price = sellService.unitWorthCents(player, material);

        return item(
                material,
                "&d" + sellService.pretty(material),
                List.of(
                        "&#bbbbbbPrice: &a" + sellService.format(price),
                        "&#bbbbbbMETA Boost: &#ff6fff" + SellService.formatMultiplier(multiplier) + "x",
                        "&#bbbbbbCategory: &#ff6fff" + sellService.categoryDisplay(material)
                )
        );
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
