package net.mineacle.core.rtp.gui;

import net.mineacle.core.rtp.service.RtpMenuItem;
import net.mineacle.core.rtp.service.RtpMenuService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class RtpMenuGui {

    public static final String ORIGINS = "origins";

    private RtpMenuGui() {
    }

    public static void open(Player player, RtpMenuService menuService, String menu) {
        Inventory inventory = Bukkit.createInventory(null, menuService.size(menu), menuService.title(menu));

        for (RtpMenuItem item : menuService.items(menu)) {
            if (item.slot() < 0 || item.slot() >= inventory.getSize()) {
                continue;
            }

            inventory.setItem(item.slot(), build(player, menuService, item));
        }

        player.openInventory(inventory);
    }

    public static boolean isOrigins(String title, RtpMenuService service) {
        if (title == null) {
            return false;
        }

        return ChatColor.stripColor(title).equals(service.rawTitle(ORIGINS));
    }

    private static ItemStack build(Player player, RtpMenuService menuService, RtpMenuItem item) {
        ItemStack stack = new ItemStack(item.material());
        ItemMeta meta = stack.getItemMeta();

        if (meta == null) {
            return stack;
        }

        meta.setDisplayName(menuService.parse(player, item.name()));
        meta.setLore(menuService.parseLore(player, item));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }
}
