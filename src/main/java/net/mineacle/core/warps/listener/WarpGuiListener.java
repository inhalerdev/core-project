package net.mineacle.core.warps.listener;

import net.mineacle.core.Core;
import net.mineacle.core.warps.model.WarpPoint;
import net.mineacle.core.warps.service.WarpService;
import net.mineacle.core.warps.service.WarpTeleportService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class WarpGuiListener implements Listener {

    private final Core core;
    private final WarpService warpService;
    private final WarpTeleportService teleportService;

    public WarpGuiListener(Core core, WarpService warpService, WarpTeleportService teleportService) {
        this.core = core;
        this.warpService = warpService;
        this.teleportService = teleportService;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!event.getView().getTitle().equals(warpService.menuTitle())) {
            return;
        }

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }

        ItemMeta meta = clicked.getItemMeta();

        if (meta == null || meta.lore() == null || meta.lore().isEmpty()) {
            return;
        }

        int slot = event.getRawSlot();

        for (WarpPoint point : warpService.warps()) {
            if (point.slot() == slot) {
                player.closeInventory();
                teleportService.teleport(player, point);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().equals(warpService.menuTitle())) {
            event.setCancelled(true);
        }
    }
}
