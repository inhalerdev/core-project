package net.mineacle.core.warps.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.model.WarpPoint;
import net.mineacle.core.warps.service.WarpService;
import net.mineacle.core.warps.service.WarpTeleportService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class WarpGuiListener implements Listener {

    private final WarpService warpService;
    private final WarpTeleportService teleportService;

    public WarpGuiListener(WarpService warpService, WarpTeleportService teleportService) {
        this.warpService = warpService;
        this.teleportService = teleportService;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());
        String warpTitle = ChatColor.stripColor(warpService.title());

        if (title == null || warpTitle == null || !title.equals(warpTitle)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        WarpPoint point = warpService.warpBySlot(slot);

        if (point == null) {
            return;
        }

        if (warpService.targetLocation(player, point) == null) {
            String message = warpService.message("world-missing")
                    .replace("%warp%", TextColor.color(point.displayName()))
                    .replace("%world%", point.worldName());
            player.sendMessage(TextColor.color(message));
            player.sendActionBar(actionBar(message));
            SoundService.guiError(player, warpService.core());
            return;
        }

        SoundService.guiClick(player, warpService.core());
        player.closeInventory();
        teleportService.begin(player, point);
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
