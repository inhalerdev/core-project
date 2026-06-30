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

public final class OriginsVoidListener implements Listener {

    private static final String ORIGINS_WORLD = "origins";
    private static final double ORIGINS_VOID_Y = -64.0D;
    private static final long RESCUE_COOLDOWN_MILLIS = 1500L;

    private final SpawnService spawnService;
    private final Map<UUID, Long> fallProtection = new HashMap<>();
    private final Map<UUID, Long> rescueCooldowns = new HashMap<>();

    public OriginsVoidListener(SpawnService spawnService) {
        this.spawnService = spawnService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!spawnService.enabled()) {
            return;
        }

        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (!player.getWorld().getName().equalsIgnoreCase(ORIGINS_WORLD)) {
            return;
        }

        if (player.getLocation().getY() > ORIGINS_VOID_Y) {
            return;
        }

        if (onCooldown(player)) {
            return;
        }

        SpawnPoint target = spawnService.selectVoidTarget();

        if (target == null) {
            sendBoth(player, spawnService.message("random-missing"));
            rescueCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + RESCUE_COOLDOWN_MILLIS);
            return;
        }

        if (!spawnService.teleport(player, target)) {
            String message = spawnService.message("world-missing")
                    .replace("%world%", target.worldName())
                    .replace("%spawn%", TextColor.color(target.displayName()));

            sendBoth(player, message);
            rescueCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + RESCUE_COOLDOWN_MILLIS);
            return;
        }

        protectFromFall(player);
        rescueCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + RESCUE_COOLDOWN_MILLIS);

        String message = spawnService.message("teleported")
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

    private boolean onCooldown(Player player) {
        Long until = rescueCooldowns.get(player.getUniqueId());

        if (until == null) {
            return false;
        }

        if (System.currentTimeMillis() <= until) {
            return true;
        }

        rescueCooldowns.remove(player.getUniqueId());
        return false;
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
