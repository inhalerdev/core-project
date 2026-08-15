package net.mineacle.core.warp.listener;

import net.mineacle.core.warp.service.WarpTeleportService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class WarpQueueListener
        implements Listener {

    private final WarpTeleportService teleportService;

    public WarpQueueListener(
            WarpTeleportService teleportService
    ) {
        this.teleportService = teleportService;
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();

        if (samePosition(
                event.getFrom(),
                to
        )) {
            return;
        }

        teleportService.handleMove(
                event.getPlayer(),
                to
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(
            PlayerTeleportEvent event
    ) {
        teleportService.handleTeleport(
                event.getPlayer(),
                event.getTo()
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onQuit(PlayerQuitEvent event) {
        teleportService.handleQuit(
                event.getPlayer()
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onDeath(PlayerDeathEvent event) {
        teleportService.handleDeath(
                event.getEntity()
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onRespawn(
            PlayerRespawnEvent event
    ) {
        teleportService.handleRespawn(
                event.getPlayer()
        );
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
