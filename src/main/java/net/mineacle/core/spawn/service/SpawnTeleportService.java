package net.mineacle.core.spawn.service;

import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.spawn.model.SpawnPoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class SpawnTeleportService {

    private final SpawnService spawnService;
    private final TeleportService teleportService;

    public SpawnTeleportService(
            SpawnService spawnService,
            TeleportService teleportService
    ) {
        this.spawnService = spawnService;
        this.teleportService = teleportService;
    }

    public void begin(
            Player player,
            SpawnPoint point
    ) {
        Location target =
                spawnService.location(point);

        if (target == null) {
            worldMissing(
                    player,
                    point
            );
            return;
        }

        int delay =
                spawnService.isCurrentWorld(
                        player,
                        point
                )
                        ? 0
                        : spawnService
                        .teleportDelaySeconds(
                                player
                        );

        teleportService.beginLocation(
                player,
                displayName(point),
                target,
                TeleportService.TeleportKind.SPAWN,
                delay,
                spawnService.cancelOnMove()
        );
    }

    public boolean force(
            Player player,
            SpawnPoint point
    ) {
        Location target =
                spawnService.location(point);

        if (target == null) {
            worldMissing(
                    player,
                    point
            );
            return false;
        }

        return teleportService.forceLocation(
                player,
                displayName(point),
                target
        );
    }

    private void worldMissing(
            Player player,
            SpawnPoint point
    ) {
        String message =
                spawnService.message(
                        "world-missing"
                )
                        .replace(
                                "%world%",
                                point.worldName()
                        )
                        .replace(
                                "%spawn%",
                                displayName(point)
                        );

        player.sendMessage(message);
        player.sendActionBar(
                net.kyori.adventure.text.serializer
                        .legacy
                        .LegacyComponentSerializer
                        .legacySection()
                        .deserialize(
                                TextColor.color(message)
                        )
        );
        SoundService.guiError(
                player,
                spawnService.core()
        );
    }

    private String displayName(
            SpawnPoint point
    ) {
        String stripped =
                TextColor.strip(
                        TextColor.color(
                                point.displayName()
                        )
                );

        return stripped == null
                || stripped.isBlank()
                ? point.id()
                : stripped;
    }
}
