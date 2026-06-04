package net.mineacle.core.servermessages.listener;

import net.mineacle.core.servermessages.service.WorldMaintenanceService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class WorldMaintenanceListener implements Listener {

    private final WorldMaintenanceService service;

    public WorldMaintenanceListener(WorldMaintenanceService service) {
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(
                Bukkit.getPluginManager().getPlugin("MineacleCore"),
                () -> service.apply(event.getPlayer()),
                20L
        );
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskLater(
                Bukkit.getPluginManager().getPlugin("MineacleCore"),
                () -> service.apply(event.getPlayer()),
                2L
        );
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Bukkit.getScheduler().runTaskLater(
                Bukkit.getPluginManager().getPlugin("MineacleCore"),
                () -> service.apply(event.getPlayer()),
                2L
        );
    }
}
