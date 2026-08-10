package net.mineacle.core.rtp.listener;

import net.mineacle.core.rtp.service.OriginRtpQueueService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** RTP queue/search lifecycle; common TeleportService owns final countdown. */
@SuppressWarnings("unused")
public final class OriginRtpMoveListener implements Listener {

    private final OriginRtpQueueService queueService;

    public OriginRtpMoveListener(OriginRtpQueueService queueService) {
        this.queueService = queueService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!queueService.active(event.getPlayer())) {
            return;
        }

        Location to = event.getTo();
        if (samePosition(event.getFrom(), to)) {
            return;
        }

        queueService.handleMove(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!queueService.active(event.getPlayer())) {
            return;
        }

        queueService.handleTeleport(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        queueService.handleQuit(event.getPlayer());
    }

    private boolean samePosition(Location from, Location to) {
        return from.getWorld() == to.getWorld()
                && Double.compare(from.getX(), to.getX()) == 0
                && Double.compare(from.getY(), to.getY()) == 0
                && Double.compare(from.getZ(), to.getZ()) == 0;
    }
}
