package net.mineacle.core.warps.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.model.WarpPoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WarpTeleportService {

    private static final String CANCELLED_MOVE_MESSAGE = "&cTeleport cancelled — you moved";

    private final WarpService warpService;
    private final Map<UUID, PendingWarp> pending = new HashMap<>();

    public WarpTeleportService(WarpService warpService) {
        this.warpService = warpService;
    }

    public boolean isPending(Player player) {
        return player != null && pending.containsKey(player.getUniqueId());
    }

    public void begin(Player player, WarpPoint point) {
        if (player == null || point == null) {
            return;
        }

        cancel(player, false);

        if (warpService.location(point) == null) {
            sendBoth(player, warpService.message("world-missing")
                    .replace("%warp%", TextColor.color(point.displayName()))
                    .replace("%world%", point.worldName()));
            SoundService.guiError(player, warpService.core());
            return;
        }

        if (warpService.instantFromWorld(player.getWorld().getName())) {
            complete(player, point);
            return;
        }

        int delay = warpService.delaySeconds(player);

        if (delay <= 0) {
            complete(player, point);
            return;
        }

        Location start = player.getLocation().clone();
        pending.put(player.getUniqueId(), new PendingWarp(point, start, delay, null));

        sendActionBar(player, warpService.message("teleport-start")
                .replace("%warp%", TextColor.color(point.displayName()))
                .replace("%seconds%", String.valueOf(delay)));
        SoundService.teleportStart(player, warpService.core());

        BukkitTask task = warpService.core().getServer().getScheduler().runTaskTimer(
                warpService.core(),
                () -> tick(player),
                20L,
                20L
        );

        pending.put(player.getUniqueId(), new PendingWarp(point, start, delay, task));
    }

    public void cancel(Player player, boolean sendMessage) {
        if (player == null) {
            return;
        }

        PendingWarp removed = pending.remove(player.getUniqueId());

        if (removed != null && removed.task() != null) {
            removed.task().cancel();
        }

        if (sendMessage) {
            sendBoth(player, CANCELLED_MOVE_MESSAGE);
            SoundService.teleportCancelled(player, warpService.core());
        }
    }

    private void tick(Player player) {
        PendingWarp current = pending.get(player.getUniqueId());

        if (current == null) {
            return;
        }

        if (!player.isOnline()) {
            cancel(player, false);
            return;
        }

        if (warpService.cancelOnMove() && movedTooFar(current.startLocation(), player.getLocation())) {
            cancel(player, true);
            return;
        }

        int nextSeconds = current.secondsRemaining() - 1;

        if (nextSeconds <= 0) {
            complete(player, current.point());
            return;
        }

        pending.put(player.getUniqueId(), new PendingWarp(current.point(), current.startLocation(), nextSeconds, current.task()));

        sendActionBar(player, warpService.message("teleport-countdown")
                .replace("%warp%", TextColor.color(current.point().displayName()))
                .replace("%seconds%", String.valueOf(nextSeconds)));
        SoundService.teleportCountdown(player, warpService.core());
    }

    private void complete(Player player, WarpPoint point) {
        PendingWarp removed = pending.remove(player.getUniqueId());

        if (removed != null && removed.task() != null) {
            removed.task().cancel();
        }

        if (!warpService.teleport(player, point)) {
            sendBoth(player, warpService.message("world-missing")
                    .replace("%warp%", TextColor.color(point.displayName()))
                    .replace("%world%", point.worldName()));
            SoundService.guiError(player, warpService.core());
            return;
        }

        sendActionBar(player, warpService.message("teleported")
                .replace("%warp%", TextColor.color(point.displayName())));
        SoundService.teleportComplete(player, warpService.core());
    }

    private boolean movedTooFar(Location start, Location current) {
        if (start == null || current == null) {
            return true;
        }
        if (start.getWorld() == null || current.getWorld() == null) {
            return true;
        }
        if (!start.getWorld().equals(current.getWorld())) {
            return true;
        }

        double distance = warpService.cancelDistance();
        return start.distanceSquared(current) > distance * distance;
    }

    private void sendBoth(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    private record PendingWarp(
            WarpPoint point,
            Location startLocation,
            int secondsRemaining,
            BukkitTask task
    ) {
    }
}
