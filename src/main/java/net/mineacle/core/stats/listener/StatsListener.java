package net.mineacle.core.stats.listener;

import net.mineacle.core.stats.service.StatsService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class StatsListener implements Listener {

    private final StatsService statsService;

    public StatsListener(StatsService statsService) {
        this.statsService = statsService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        statsService.startSession(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        statsService.stopSession(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        statsService.switchWorld(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        statsService.recordDeath(event.getEntity());
    }
}
