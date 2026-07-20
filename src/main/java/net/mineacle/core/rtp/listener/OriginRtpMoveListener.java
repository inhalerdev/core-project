package net.mineacle.core.rtp.listener;

import net.mineacle.core.rtp.service.OriginRtpQueueService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class OriginRtpMoveListener
        implements Listener {

    private final OriginRtpQueueService queueService;

    public OriginRtpMoveListener(
            OriginRtpQueueService queueService
    ) {
        this.queueService = queueService;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();

        if (to == null
                || sameBlock(
                event.getFrom(),
                to
        )) {
            return;
        }

        queueService.handleMove(
                event.getPlayer()
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            return;
        }

        queueService.handleTeleport(
                event.getPlayer(),
                event.getTo()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(
            PlayerChangedWorldEvent event
    ) {
        queueService.handleMove(
                event.getPlayer()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        queueService.handleQuit(
                event.getPlayer()
        );
    }

    private boolean sameBlock(
            Location from,
            Location to
    ) {
        return from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
