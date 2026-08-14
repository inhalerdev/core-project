package net.mineacle.core.rtp.listener;

import net.mineacle.core.rtp.service.OriginRtpQueueService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class OriginRtpMoveListener
        implements Listener {

    private final OriginRtpQueueService queueService;

    public OriginRtpMoveListener(
            OriginRtpQueueService queueService
    ) {
        this.queueService = queueService;
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {
        if (queueService.trackingMovement(
                event.getPlayer()
        )) {
            Location to = event.getTo();

            if (!samePosition(
                    event.getFrom(),
                    to
            )) {
                queueService.handleMove(
                        event.getPlayer(),
                        to
                );
            }
        }
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        if (queueService.trackingMovement(
                event.getPlayer()
        )) {
            queueService.handleTeleport(
                    event.getPlayer(),
                    event.getTo()
            );
        }
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        queueService.handleQuit(event.getPlayer());
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        queueService.handleDeath(event.getEntity());
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        queueService.handleRespawn(event.getPlayer());
    }

    private boolean samePosition(
            Location from,
            Location to
    ) {
        return from.getWorld() == to.getWorld()
                && Double.compare(
                from.getX(),
                to.getX()
        ) == 0
                && Double.compare(
                from.getY(),
                to.getY()
        ) == 0
                && Double.compare(
                from.getZ(),
                to.getZ()
        ) == 0;
    }
}
