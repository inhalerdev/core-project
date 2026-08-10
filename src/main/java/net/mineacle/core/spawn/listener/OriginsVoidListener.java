package net.mineacle.core.spawn.listener;

import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import net.mineacle.core.spawn.service.SpawnTeleportService;
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

@SuppressWarnings("unused")
public final class OriginsVoidListener
        implements Listener {

    private static final String ORIGINS_WORLD =
            "origins";
    private static final double ORIGINS_VOID_Y =
            -64.0D;
    private static final long RESCUE_COOLDOWN_MILLIS =
            1500L;

    private final SpawnService spawnService;
    private final SpawnTeleportService teleportService;
    private final Map<UUID, Long> fallProtection =
            new HashMap<>();
    private final Map<UUID, Long> rescueCooldowns =
            new HashMap<>();

    public OriginsVoidListener(
            SpawnService spawnService,
            SpawnTeleportService teleportService
    ) {
        this.spawnService = spawnService;
        this.teleportService = teleportService;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerMove(
            PlayerMoveEvent event
    ) {
        Player player = event.getPlayer();

        if (!spawnService.enabled()
                || player.getGameMode()
                == GameMode.SPECTATOR
                || !player.getWorld()
                .getName()
                .equalsIgnoreCase(
                        ORIGINS_WORLD
                )
                || player.getLocation().getY()
                > ORIGINS_VOID_Y
                || onCooldown(player)) {
            return;
        }

        SpawnPoint target =
                spawnService.selectVoidTarget();

        rescueCooldowns.put(
                player.getUniqueId(),
                System.currentTimeMillis()
                        + RESCUE_COOLDOWN_MILLIS
        );

        if (target == null) {
            player.sendMessage(
                    spawnService.message(
                            "random-missing"
                    )
            );
            return;
        }

        if (teleportService.force(
                player,
                target
        )) {
            protectFromFall(player);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onEntityDamage(
            EntityDamageEvent event
    ) {
        if (event.getCause()
                != EntityDamageEvent
                .DamageCause
                .FALL) {
            return;
        }

        Entity entity = event.getEntity();

        if (!(entity instanceof Player player)) {
            return;
        }

        Long until =
                fallProtection.get(
                        player.getUniqueId()
                );

        if (until == null) {
            return;
        }

        if (System.currentTimeMillis() > until) {
            fallProtection.remove(
                    player.getUniqueId()
            );
            return;
        }

        event.setCancelled(true);
    }

    private boolean onCooldown(Player player) {
        Long until =
                rescueCooldowns.get(
                        player.getUniqueId()
                );

        if (until == null) {
            return false;
        }

        if (System.currentTimeMillis() <= until) {
            return true;
        }

        rescueCooldowns.remove(
                player.getUniqueId()
        );
        return false;
    }

    private void protectFromFall(Player player) {
        long durationMillis =
                spawnService
                        .voidFallProtectionSeconds()
                        * 1000L;

        fallProtection.put(
                player.getUniqueId(),
                System.currentTimeMillis()
                        + durationMillis
        );
    }
}
