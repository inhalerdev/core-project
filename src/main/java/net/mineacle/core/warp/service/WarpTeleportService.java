package net.mineacle.core.warp.service;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warp.model.WarpPoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WarpTeleportService {

    private final Core core;
    private final WarpService warpService;
    private final Map<UUID, BukkitRunnable> pending = new HashMap<>();

    public WarpTeleportService(Core core, WarpService warpService) {
        this.core = core;
        this.warpService = warpService;
    }

    public void teleport(Player player, WarpPoint point) {
        cancel(player, false);

        int seconds = warpService.countdownSeconds(player);

        if (seconds <= 0) {
            complete(player, point);
            return;
        }

        Location origin = player.getLocation().clone();
        double allowedDistance = Math.max(0.01D, core.getConfig().getDouble("warps.teleport.cancel-distance", 1.0D));

        sendActionBar(player, warpService.startingMessage(point.displayName(), seconds));
        SoundService.teleportStart(player, core);

        BukkitRunnable task = new BukkitRunnable() {
            private int remaining = seconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    WarpTeleportService.this.cancel(player, false);
                    this.cancel();
                    return;
                }

                if (moved(origin, player.getLocation(), allowedDistance)) {
                    WarpTeleportService.this.cancel(player, true);
                    this.cancel();
                    return;
                }

                remaining--;

                if (remaining <= 0) {
                    WarpTeleportService.this.cancel(player, false);
                    complete(player, point);
                    this.cancel();
                    return;
                }

                sendActionBar(player, warpService.startingMessage(point.displayName(), remaining));
                SoundService.teleportCountdown(player, core);
            }
        };

        pending.put(player.getUniqueId(), task);
        task.runTaskTimer(core, 20L, 20L);
    }

    public void cancel(Player player, boolean notify) {
        BukkitRunnable task = pending.remove(player.getUniqueId());

        if (task != null) {
            task.cancel();
        }

        if (notify) {
            sendActionBar(player, warpService.cancelledMessage());
            player.sendMessage(warpService.cancelledMessage());
            SoundService.teleportCancelled(player, core);
        }
    }

    public void cancelAll() {
        for (BukkitRunnable task : pending.values()) {
            if (task != null) {
                task.cancel();
            }
        }

        pending.clear();
    }

    private void complete(Player player, WarpPoint point) {
        player.teleport(warpService.targetLocation(player, point));
        sendActionBar(player, warpService.teleportMessage(point.displayName()));
        player.sendMessage(warpService.teleportMessage(point.displayName()));
        SoundService.teleportComplete(player, core);
    }

    private boolean moved(Location origin, Location current, double allowedDistance) {
        if (origin == null || current == null) {
            return true;
        }

        if (origin.getWorld() == null || current.getWorld() == null) {
            return true;
        }

        if (!origin.getWorld().equals(current.getWorld())) {
            return true;
        }

        return origin.distanceSquared(current) > allowedDistance * allowedDistance;
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message)));
    }
}
