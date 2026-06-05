package net.mineacle.core.spawn.listener;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

public final class SpawnJoinQuitListener implements Listener {

    private final SpawnService spawnService;

    public SpawnJoinQuitListener(SpawnService spawnService) {
        this.spawnService = spawnService;
    }

    @EventHandler
    public void onPlayerSpawnLocation(PlayerSpawnLocationEvent event) {
        SpawnPoint point = null;

        if (!event.getPlayer().hasPlayedBefore() && spawnService.firstJoinEnabled()) {
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

        World world = location.getWorld();
        world.getChunkAtAsync(location).thenRun(() -> spawnService.core().getServer().getScheduler().runTask(
                spawnService.core(),
                () -> event.setSpawnLocation(location)
        ));
    }
}
