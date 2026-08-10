package net.mineacle.core.warp.service;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.warp.model.WarpPoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class WarpTeleportService {

    private final WarpService warpService;
    private final TeleportService teleportService;

    public WarpTeleportService(
            WarpService warpService,
            TeleportService teleportService
    ) {
        this.warpService = warpService;
        this.teleportService = teleportService;
    }

    public void teleport(
            Player player,
            WarpPoint point
    ) {
        Location target =
                warpService.targetLocation(
                        player,
                        point
                );

        teleportService.beginLocation(
                player,
                displayName(point),
                target,
                TeleportService.TeleportKind.WARP,
                Math.max(
                        0,
                        warpService
                                .countdownSeconds(
                                        player
                                )
                ),
                true
        );
    }

    private String displayName(
            WarpPoint point
    ) {
        String stripped =
                TextColor.strip(
                        TextColor.color(
                                point.displayName()
                        )
                );

        return stripped.isBlank()
                ? point.key()
                : stripped;
    }
}
