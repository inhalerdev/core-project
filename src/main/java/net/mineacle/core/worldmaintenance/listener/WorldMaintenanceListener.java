package net.mineacle.core.worldmaintenance.listener;

import net.mineacle.core.Core;
import net.mineacle.core.worldmaintenance.service.WorldMaintenanceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class WorldMaintenanceListener implements Listener {

    private final Core core;
    private final WorldMaintenanceService service;

    public WorldMaintenanceListener(Core core, WorldMaintenanceService service) {
        this.core = core;
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        core.getServer().getScheduler().runTaskLater(core, () -> service.apply(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        core.getServer().getScheduler().runTaskLater(core, () -> service.apply(event.getPlayer()), 2L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        core.getServer().getScheduler().runTaskLater(core, () -> service.apply(event.getPlayer()), 2L);
    }
}
