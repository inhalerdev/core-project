package net.mineacle.core.spawn.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SpawnVoidListener implements Listener {

    private final SpawnService spawnService;
    private final Map<UUID, Long> fallProtection = new HashMap<>();

    public SpawnVoidListener(SpawnService spawnService) {
        this.spawnService = spawnService;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!spawnService.voidEnabled()) {
            return;
        }

        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (!spawnService.voidWorldAllowed(player.getWorld().getName())) {
            return;
        }

        if (player.getLocation().getY() > spawnService.voidTriggerY()) {
            return;
        }

        SpawnPoint target = spawnService.selectVoidTarget();

        if (target == null) {
            sendBoth(player, spawnService.message("void-missing"));
            return;
        }

        if (!spawnService.teleport(player, target)) {
            String message = spawnService.message("world-missing")
                    .replace("%world%", target.worldName())
                    .replace("%spawn%", TextColor.color(target.displayName()));

            sendBoth(player, message);
            return;
        }

        protectFromFall(player);

        String message = spawnService.message("void-teleported")
                .replace("%spawn%", TextColor.color(target.displayName()));

        sendBoth(player, message);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }

        Entity entity = event.getEntity();

        if (!(entity instanceof Player player)) {
            return;
        }

        Long until = fallProtection.get(player.getUniqueId());

        if (until == null) {
            return;
        }

        if (System.currentTimeMillis() > until) {
            fallProtection.remove(player.getUniqueId());
            return;
        }

        event.setCancelled(true);
    }

    private void protectFromFall(Player player) {
        long durationMillis = spawnService.voidFallProtectionSeconds() * 1000L;
        fallProtection.put(player.getUniqueId(), System.currentTimeMillis() + durationMillis);
    }

    private void sendBoth(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}