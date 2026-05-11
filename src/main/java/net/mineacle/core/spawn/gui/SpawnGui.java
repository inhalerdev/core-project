package net.mineacle.core.spawn.gui;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class SpawnGui {

    private SpawnGui() {
    }

    public static void open(Player player, SpawnService spawnService) {
        Inventory inventory = Bukkit.createInventory(null, spawnService.size(), spawnService.title());

        for (SpawnPoint point : spawnService.spawnPoints()) {
            if (!point.enabled()) {
                continue;
            }

            boolean current = spawnService.isCurrentWorld(player, point);
            int online = spawnService.onlineInWorld(point);

            inventory.setItem(point.slot(), spawnItem(
                    current,
                    point.displayName(),
                    spawnService.applyLorePlaceholders(
                            current ? spawnService.currentSpawnLore() : spawnService.availableSpawnLore(),
                            point,
                            online
                    )
            ));
        }

        if (spawnService.randomEnabled()) {
            inventory.setItem(spawnService.randomSlot(), item(
                    Material.NETHER_STAR,
                    spawnService.randomDisplayName(),
                    spawnService.randomLore()
            ));
        }

        player.openInventory(inventory);
    }

    private static ItemStack spawnItem(boolean current, String displayName, List<String> lore) {
        return item(
                current ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME,
                displayName,
                lore
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