package net.mineacle.core.spawn.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class SpawnJoinQuitListener implements Listener {

    private final SpawnService spawnService;

    public SpawnJoinQuitListener(SpawnService spawnService) {
        this.spawnService = spawnService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!spawnService.loginRerouteEnabled()) {
            return;
        }

        spawnService.core().getServer().getScheduler().runTaskLater(
                spawnService.core(),
                () -> rerouteIfNeeded(player),
                spawnService.loginRerouteDelayTicks()
        );
    }

    private void rerouteIfNeeded(Player player) {
        if (!player.isOnline()) {
            return;
        }

        if (!spawnService.isSpawnWorld(player.getWorld().getName())) {
            return;
        }

        SpawnPoint point = spawnService.selectRandomPoint();

        if (point == null) {
            if (spawnService.loginRerouteSendMessage()) {
                sendBoth(player, spawnService.message("login-reroute-missing"));
            }

            return;
        }

        if (!spawnService.teleport(player, point)) {
            if (spawnService.loginRerouteSendMessage()) {
                String message = spawnService.message("world-missing")
                        .replace("%world%", point.worldName())
                        .replace("%spawn%", TextColor.color(point.displayName()));

                sendBoth(player, message);
            }

            return;
        }

        if (spawnService.loginRerouteSendMessage()) {
            String message = spawnService.message("login-rerouted")
                    .replace("%spawn%", TextColor.color(point.displayName()));

            sendBoth(player, message);
        }
    }

    private void sendBoth(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}