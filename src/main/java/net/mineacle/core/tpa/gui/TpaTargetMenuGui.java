package net.mineacle.core.tpa.gui;

import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class TpaTargetMenuGui {

    public static final String TITLE = "Confirm Request";
    public static final int CANCEL_SLOT = 10;
    public static final int REGION_SLOT = 12;
    public static final int PLAYER_SLOT = 13;
    public static final int LOCATION_SLOT = 14;
    public static final int CONFIRM_SLOT = 16;

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String BODY = "&#bbbbbb";

    private TpaTargetMenuGui() {
    }

    public static void open(Player viewer, Player target) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                GuiText.title(TITLE)
        );

        inventory.setItem(
                CANCEL_SLOT,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        List.of(BODY + "Cancel this teleport request")
                )
        );
        inventory.setItem(
                REGION_SLOT,
                item(
                        regionMaterial(target),
                        PRIMARY + "Region",
                        List.of(BODY + regionName(target))
                )
        );
        inventory.setItem(
                PLAYER_SLOT,
                playerHead(
                        target,
                        List.of(SECONDARY + DisplayNames.displayName(target))
                )
        );
        inventory.setItem(
                LOCATION_SLOT,
                item(
                        Material.FEATHER,
                        PRIMARY + "Location",
                        List.of(BODY + target.getWorld().getName())
                )
        );
        inventory.setItem(
                CONFIRM_SLOT,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aConfirm",
                        List.of(
                                BODY + "Send " + SECONDARY + DisplayNames.displayName(target),
                                BODY + "a teleport request"
                        )
                )
        );

        viewer.openInventory(inventory);
    }

    public static boolean isTitle(String plainTitle) {
        return GuiText.plain(TITLE).equalsIgnoreCase(plainTitle);
    }

    private static Material regionMaterial(Player player) {
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
        World.Environment environment = player.getWorld().getEnvironment();
        if (environment == World.Environment.NETHER) {
            return "Nether";
        }
        if (environment == World.Environment.THE_END) {
            return "The End";
        }
        return "Overworld";
    }

    private static ItemStack playerHead(
            Player owner,
            List<String> lore
    ) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(owner);
        GuiText.apply(meta, PRIMARY + "Player", lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
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
