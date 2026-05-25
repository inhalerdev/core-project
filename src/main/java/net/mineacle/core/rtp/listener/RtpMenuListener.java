package net.mineacle.core.rtp.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.rtp.gui.RtpMenuGui;
import net.mineacle.core.rtp.service.OriginRtpQueueService;
import net.mineacle.core.rtp.service.RtpMenuItem;
import net.mineacle.core.rtp.service.RtpMenuService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class RtpMenuListener implements Listener {

    private final Core core;
    private final RtpMenuService menuService;
    private final OriginRtpQueueService queueService;

    public RtpMenuListener(Core core, RtpMenuService menuService, OriginRtpQueueService queueService) {
        this.core = core;
        this.menuService = menuService;
        this.queueService = queueService;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!RtpMenuGui.isOrigins(title, menuService)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        RtpMenuItem item = menuService.itemAt(RtpMenuGui.ORIGINS, slot);

        if (item == null || item.destination() == null || item.destination().isBlank()) {
            return;
        }

        SoundService.guiClick(player, core);
        MenuHistory.openWithoutBackTrigger(core, player, player::closeInventory);
        queueService.request(player, item.destination());
    }
}
