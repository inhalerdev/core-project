package net.mineacle.core.orders.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class OrderCreateGui {

    public static final int SIZE = 27;
    public static final int ITEM_SLOT = 13;
    public static final int CANCEL_SLOT = 11;
    public static final int CREATE_SLOT = 15;

    private OrderCreateGui() {
    }

    public static void open(Player player) {
        Inventory inventory = Bukkit.createInventory(null, SIZE, title());

        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand == null || hand.getType() == Material.AIR) {
            inventory.setItem(ITEM_SLOT, OrdersMainGui.item(Material.BARRIER, cfg("orders.gui.create.empty-name", "&cNO ITEM"), List.of(
                    cfg("orders.gui.create.empty-lore", "&fHold the item you want to order")
            )));
        } else {
            inventory.setItem(ITEM_SLOT, OrdersMainGui.item(hand.getType(), cfg("orders.gui.create.item-name", "&aSELECTED ITEM"), List.of(
                    cfg("orders.gui.create.item-lore", "&fThis is the item players will deliver")
            )));
        }

        inventory.setItem(CANCEL_SLOT, OrdersMainGui.item(Material.RED_STAINED_GLASS_PANE, cfg("orders.gui.create.cancel-name", "&cCANCEL"), List.of(
                cfg("orders.gui.create.cancel-lore", "&fClick to cancel")
        )));

        inventory.setItem(CREATE_SLOT, OrdersMainGui.item(Material.LIME_STAINED_GLASS_PANE, cfg("orders.gui.create.create-name", "&aCREATE ORDER"), List.of(
                cfg("orders.gui.create.create-lore-1", "&fClick and type amount price"),
                cfg("orders.gui.create.create-lore-2", "&#bbbbbbExample: &f64 10")
        )));

        player.openInventory(inventory);
    }

    public static String title() {
        return TextColor.color(cfg("orders.gui.titles.create", "CREATE ORDER"));
    }

    public static boolean isTitle(String title) {
        return title != null && title.equals(org.bukkit.ChatColor.stripColor(title()));
    }

    private static String cfg(String path, String fallback) {
        Core core = Core.instance();

        if (core == null) {
            return fallback;
        }

        return core.getConfig().getString(path, fallback);
    }
}
