package net.mineacle.core.rtp.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.rtp.gui.RtpMenuGui;
import net.mineacle.core.rtp.service.OriginRtpQueueService;
import net.mineacle.core.rtp.service.RtpMenuItem;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class RtpMenuListener
        implements Listener {

    private final Core core;
    private final OriginRtpQueueService queueService;

    public RtpMenuListener(
            Core core,
            OriginRtpQueueService queueService
    ) {
        this.core = core;
        this.queueService = queueService;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onClick(InventoryClickEvent event) {
        RtpMenuGui.Holder holder =
                RtpMenuGui.holder(
                        event.getView()
                                .getTopInventory()
                );

        if (holder == null) {
            return;
        }

        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        RtpMenuItem item = holder.itemAt(slot);

        if (item == null
                || item.destination() == null
                || item.destination().isBlank()) {
            return;
        }

        SoundService.guiSelect(player, core);
        MenuHistory.close(core, player);

        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (player.isOnline()) {
                        queueService.request(
                                player,
                                item.destination()
                        );
                    }
                }
        );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onDrag(InventoryDragEvent event) {
        if (RtpMenuGui.holder(
                event.getView()
                        .getTopInventory()
        ) != null) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }
}
