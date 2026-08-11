package net.mineacle.core.spawn.gui;

import net.mineacle.core.common.gui.GuiText;
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

    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";

    private SpawnGui() {
    }

    public static void open(Player player, SpawnService spawnService) {
        Inventory inventory = Bukkit.createInventory(
                null,
                spawnService.size(),
                GuiText.title(spawnService.title())
        );

        for (SpawnPoint point : spawnService.spawnPoints()) {
            if (!point.enabled()) {
                continue;
            }

            boolean current = spawnService.isCurrentWorld(player, point);
            int online = spawnService.onlineInWorld(point);

            inventory.setItem(
                    point.slot(),
                    spawnItem(
                            current,
                            normalizePalette(
                                    point.displayName()
                            ),
                            normalizeLore(
                                    spawnService.applyLorePlaceholders(
                                            current
                                                    ? spawnService.currentSpawnLore()
                                                    : spawnService.availableSpawnLore(),
                                            point,
                                            online
                                    )
                            )
                    )
            );
        }

        if (spawnService.randomEnabled()) {
            inventory.setItem(
                    spawnService.randomSlot(),
                    item(
                            Material.NETHER_STAR,
                            normalizePalette(
                                    spawnService.randomDisplayName()
                            ),
                            normalizeLore(
                                    spawnService.randomLore()
                            )
                    )
            );
        }

        player.openInventory(inventory);
    }

    private static List<String> normalizeLore(
            List<String> lore
    ) {
        if (lore == null || lore.isEmpty()) {
            return List.of();
        }

        return lore.stream()
                .map(SpawnGui::normalizePalette)
                .toList();
    }

    private static String normalizePalette(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        return input
                .replace("&#8436FE", SECONDARY)
                .replace("&#8436fe", SECONDARY)
                .replace("&#ff55ff", SECONDARY)
                .replace("&#FF55FF", SECONDARY)
                .replace("&d", SECONDARY)
                .replace("&#ff88ff", ACCENT)
                .replace("&#FF88FF", ACCENT)
                .replace("&a", ACCENT);
    }

    private static ItemStack spawnItem(
            boolean current,
            String displayName,
            List<String> lore
    ) {
        return item(
                current ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME,
                displayName,
                lore
        );
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

        GuiText.apply(meta, name, lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
