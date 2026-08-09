package net.mineacle.core.homes.listener;

import net.mineacle.core.homes.service.TeleportService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public final class HomesMoveListener implements Listener {

    private final TeleportService teleportService;

    public HomesMoveListener(TeleportService teleportService) {
        this.teleportService = teleportService;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        teleportService.handleMove(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        teleportService.cancel(event.getPlayer().getUniqueId());
    }
}