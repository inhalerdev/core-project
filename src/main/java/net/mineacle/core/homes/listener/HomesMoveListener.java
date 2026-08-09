package net.mineacle.core.homes.listener;

import net.mineacle.core.homes.service.TeleportService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

@SuppressWarnings("unused")
public final class HomesMoveListener implements Listener {

    private final TeleportService teleportService;

    public HomesMoveListener(
            TeleportService teleportService
    ) {
        this.teleportService = teleportService;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerMove(PlayerMoveEvent event) {
        teleportService.handleMove(
                event.getPlayer(),
                event.getTo()
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        teleportService.handleMove(
                event.getPlayer(),
                event.getTo()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        teleportService.cancel(
                event.getPlayer().getUniqueId()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        teleportService.cancel(
                event.getPlayer().getUniqueId()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        teleportService.cancel(
                event.getEntity().getUniqueId()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        teleportService.cancel(
                event.getPlayer().getUniqueId()
        );
    }
}
