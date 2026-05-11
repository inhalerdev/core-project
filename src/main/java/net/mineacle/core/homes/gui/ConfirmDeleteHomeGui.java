package net.mineacle.core.homes.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class ConfirmDeleteHomeGui {

    public static final int CANCEL_SLOT = 11;
    public static final int ACTION_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private ConfirmDeleteHomeGui() {
    }

    public static void openPlayerDelete(Core core, Player player, int id, String displayName) {
        String title = ChatColor.translateAlternateColorCodes('&', core.getMessage("homes.gui.delete-title"));
        Inventory inventory = Bukkit.createInventory(null, 27, title);

        inventory.setItem(CANCEL_SLOT, item(
                Material.RED_STAINED_GLASS_PANE,
                "&cCancel",
                List.of(
                        "&7Do not continue",
                        "&7Click to cancel this action"
                )
        ));

        inventory.setItem(ACTION_SLOT, item(
                Material.RED_DYE,
                "&cDelete " + displayName,
                List.of(
                        "&7This action needs confirmation",
                        "&7Use the green pane to continue"
                )
        ));

        inventory.setItem(CONFIRM_SLOT, item(
                Material.LIME_STAINED_GLASS_PANE,
                "&aConfirm",
                List.of(
                        "&7Click once to ready this action",
                        "&7Click again to confirm"
                )
        ));

        player.openInventory(inventory);
    }

    public static void openTeamDelete(Core core, Player player) {
        String title = ChatColor.translateAlternateColorCodes('&', core.getMessage("homes.gui.team-delete-title"));
        Inventory inventory = Bukkit.createInventory(null, 27, title);

        inventory.setItem(CANCEL_SLOT, item(
                Material.RED_STAINED_GLASS_PANE,
                "&cCancel",
                List.of(
                        "&7Do not continue",
                        "&7Click to cancel this action"
                )
        ));

        inventory.setItem(ACTION_SLOT, item(
                Material.RED_DYE,
                "&cDelete Team Home",
                List.of(
                        "&7This action needs confirmation",
                        "&7Use the green pane to continue"
                )
        ));

        inventory.setItem(CONFIRM_SLOT, item(
                Material.LIME_STAINED_GLASS_PANE,
                "&aConfirm",
                List.of(
                        "&7Click once to ready this action",
                        "&7Click again to confirm"
                )
        ));

        player.openInventory(inventory);
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