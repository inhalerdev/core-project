package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public final class SellGui {

    public static final int SIZE = 36;

    private SellGui() {
    }

    public static void open(Core core, Player player) {
        Inventory inventory = Bukkit.createInventory(null, SIZE, title(core));
        player.openInventory(inventory);
    }

    public static String title(Core core) {
        return TextColor.color(core.getConfig().getString("sell.gui.title", "Place Items In Here To Sell"));
    }
}
