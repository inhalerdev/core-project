package net.mineacle.core.warps.gui;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.model.WarpPoint;
import net.mineacle.core.warps.service.WarpService;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class WarpGui {

    private WarpGui() {
    }

    public static void open(Core core, org.bukkit.entity.Player player, WarpService service) {
        Inventory inventory = Bukkit.createInventory(null, service.menuSize(), service.menuTitle());

        for (WarpPoint point : service.warps()) {
            if (point.slot() < 0 || point.slot() >= inventory.getSize()) {
                continue;
            }

            ItemStack item = new ItemStack(point.material());
            ItemMeta meta = item.getItemMeta();

            if (meta != null) {
                meta.displayName(legacy(point.displayName()));
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(legacy("&#bbbbbbClick to warp"));
                lore.add(legacy("&8" + point.key()));
                meta.lore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }

            inventory.setItem(point.slot(), item);
        }

        player.openInventory(inventory);
    }

    private static net.kyori.adventure.text.Component legacy(String value) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(value));
    }
}
