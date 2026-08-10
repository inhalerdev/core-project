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
public final class SpawnVoidListener
        implements Listener {

    private final SpawnService spawnService;
    private final SpawnTeleportService teleportService;
    private final Map<UUID, Long> fallProtection =
            new HashMap<>();

    public SpawnVoidListener(
            SpawnService spawnService,
            SpawnTeleportService teleportService
    ) {
        this.spawnService = spawnService;
        this.teleportService = teleportService;
    }

    @EventHandler(
            priority = EventPriority.NORMAL,
            ignoreCancelled = true
    )
    public void onPlayerMove(
            PlayerMoveEvent event
    ) {
        Player player = event.getPlayer();

        if (!spawnService.voidEnabled()
                || player.getGameMode()
                == GameMode.SPECTATOR
                || !spawnService.voidWorldAllowed(
                player.getWorld().getName()
        )
                || player.getLocation().getY()
                > spawnService.voidTriggerY()) {
            return;
        }

        SpawnPoint target =
                spawnService.selectVoidTarget();

        if (target == null) {
            player.sendMessage(
                    spawnService.message(
                            "void-missing"
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
