package net.mineacle.core.rtp.listener;

import net.mineacle.core.rtp.service.OriginRtpQueueService;
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
        if (event.getTo() == null
                || event.getFrom().getWorld()
                == event.getTo().getWorld()
                && event.getFrom().getX()
                == event.getTo().getX()
                && event.getFrom().getY()
                == event.getTo().getY()
                && event.getFrom().getZ()
                == event.getTo().getZ()) {
            return;
        }

        queueService.handleMove(
                event.getPlayer()
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

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause()
                == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }

        queueService.handleMove(
                event.getPlayer()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        queueService.cancel(
                event.getPlayer(),
                false
        );
    }
}
