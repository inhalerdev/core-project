package net.mineacle.core.spawn.listener;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class SpawnJoinQuitListener implements Listener {

    private final SpawnService spawnService;

    public SpawnJoinQuitListener(SpawnService spawnService) {
        this.spawnService = spawnService;
    }

    @EventHandler
    public void onAsyncPlayerSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        SpawnPoint point = null;

        if (event.isNewPlayer() && spawnService.firstJoinEnabled()) {
            point = spawnService.selectFirstJoinTarget();
        } else if (spawnService.loginRerouteEnabled()) {
            Location current = event.getSpawnLocation();

            if (current.getWorld() != null && spawnService.isSpawnWorld(current.getWorld().getName())) {
                point = spawnService.selectRandomPoint();
            }
        }

        if (point == null) {
            return;
        }

        Location location = spawnService.location(point);

        if (location == null || location.getWorld() == null) {
            return;
        }

        event.setSpawnLocation(location);
    }
}
