package net.mineacle.core.tpa.gui;

import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public final class TpaTargetMenuGui {

    public static final String TITLE = "Confirm Request";

    public static final int CANCEL_SLOT = 10;
    public static final int REGION_SLOT = 12;
    public static final int PLAYER_SLOT = 13;
    public static final int LOCATION_SLOT = 14;
    public static final int CONFIRM_SLOT = 16;

    private TpaTargetMenuGui() {
    }

    public static void open(Player viewer, Player target) {
        Inventory inventory = Bukkit.createInventory(null, 27, TextColor.color(TITLE));

        inventory.setItem(CANCEL_SLOT, item(
                Material.RED_STAINED_GLASS_PANE,
                "&cCancel",
                List.of("&#bbbbbbClick to cancel the teleport")
        ));

        inventory.setItem(REGION_SLOT, item(
                regionMaterial(target),
                "&dRegion",
                List.of("&#bbbbbb" + regionName(target))
        ));

        inventory.setItem(PLAYER_SLOT, playerHead(
                target,
                "&dPlayer",
                List.of("&#ff6fff" + DisplayNames.displayName(target))
        ));

        inventory.setItem(LOCATION_SLOT, item(
                Material.FEATHER,
                "&dLocation",
                List.of("&#bbbbbb" + locationName(target))
        ));

        inventory.setItem(CONFIRM_SLOT, item(
                Material.LIME_STAINED_GLASS_PANE,
                "&dConfirm",
                List.of("&#bbbbbbClick to send &#ff6fff" + DisplayNames.displayName(target) + " &#bbbbbba TPA request")
        ));

        viewer.openInventory(inventory);
    }

    public static boolean isTitle(String strippedTitle) {
        return strippedTitle != null && strippedTitle.equalsIgnoreCase(TITLE);
    }

    private static Material regionMaterial(Player player) {
        if (player == null || player.getWorld() == null) {
            return Material.GRASS_BLOCK;
        }

        World.Environment environment = player.getWorld().getEnvironment();

        if (environment == World.Environment.NETHER) {
            return Material.NETHERRACK;
        }

        if (environment == World.Environment.THE_END) {
            return Material.END_STONE;
        }

        return Material.GRASS_BLOCK;
    }

    private static String regionName(Player player) {
        if (player == null || player.getWorld() == null) {
            return "Unknown";
        }

        World.Environment environment = player.getWorld().getEnvironment();

        if (environment == World.Environment.NETHER) {
            return "Nether";
        }

        if (environment == World.Environment.THE_END) {
            return "End";
        }

        return "Overworld";
    }

    private static String locationName(Player player) {
        if (player == null || player.getWorld() == null) {
            return "Unknown";
        }

        return player.getWorld().getName();
    }

    private static ItemStack playerHead(Player owner, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(owner);
        meta.setDisplayName(color(name));
        meta.setLore(coloredLore(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(name));
        meta.setLore(coloredLore(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static List<String> coloredLore(List<String> lore) {
        List<String> output = new ArrayList<>();

        for (String line : lore) {
            output.add(color(line));
        }

        return output;
    }

    private static String color(String input) {
        return TextColor.color(input);
    }
}
