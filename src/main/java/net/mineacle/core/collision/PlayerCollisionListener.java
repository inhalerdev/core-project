package net.mineacle.core.collision;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class PlayerCollisionListener
        implements Listener {

    private final PlayerCollisionService service;

    public PlayerCollisionListener(
            PlayerCollisionService service
    ) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.scheduleApply(
                event.getPlayer()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(
            PlayerChangedWorldEvent event
    ) {
        service.scheduleApply(
                event.getPlayer()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(
            PlayerRespawnEvent event
    ) {
        service.apply(
                event.getPlayer(),
                event.getRespawnLocation()
                        .getWorld()
        );
        service.scheduleApply(
                event.getPlayer()
        );
    }
}
