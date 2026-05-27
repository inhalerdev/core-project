package net.mineacle.core.warps.gui;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.model.WarpPoint;
import net.mineacle.core.warps.service.WarpService;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class WarpGui {

    private WarpGui() {
    }

    public static void open(Player player, WarpService warpService) {
        Inventory inventory = Bukkit.createInventory(null, warpService.size(), warpService.title());

        for (WarpPoint point : warpService.availableWarps()) {
            if (point.slot() < 0 || point.slot() >= inventory.getSize()) {
                continue;
            }

            inventory.setItem(point.slot(), item(player, warpService, point));
        }

        player.openInventory(inventory);
    }

    private static ItemStack item(Player player, WarpService warpService, WarpPoint point) {
        ItemStack item = new ItemStack(point.material());
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(TextColor.color(point.displayName()));

            List<String> lore = new ArrayList<>();
            for (String line : warpService.lore(point, player)) {
                lore.add(TextColor.color(line));
            }
            meta.setLore(lore);

            item.setItemMeta(meta);
        }

        return item;
    }
}
