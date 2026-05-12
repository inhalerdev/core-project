package net.mineacle.core.spawn.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.model.SpawnPoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SpawnTeleportService {

    private static final String CANCELLED_MOVE_MESSAGE = "&cTeleport cancelled — you moved";

    private final SpawnService spawnService;
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();

    public SpawnTeleportService(SpawnService spawnService) {
        this.spawnService = spawnService;
    }

    public boolean isPending(Player player) {
        return pendingTeleports.containsKey(player.getUniqueId());
    }

    public void begin(Player player, SpawnPoint point) {
        cancel(player, false);

        Location target = spawnService.location(point);

        if (target == null) {
            String message = spawnService.message("world-missing")
                    .replace("%world%", point.worldName())
                    .replace("%spawn%", TextColor.color(point.displayName()));

            sendActionBar(player, message);
            SoundService.guiError(player, spawnService.core());
            return;
        }

        int delay = spawnService.teleportDelaySeconds(player);

        if (delay <= 0) {
            SoundService.teleportStart(player, spawnService.core());
            complete(player, point);
            return;
        }

        Location start = player.getLocation().clone();
        String displayName = TextColor.color(point.displayName());

        PendingTeleport pending = new PendingTeleport(point, start, delay, null);
        pendingTeleports.put(player.getUniqueId(), pending);

        String startMessage = spawnService.message("teleport-start")
                .replace("%spawn%", displayName)
                .replace("%seconds%", String.valueOf(delay));

        sendActionBar(player, startMessage);
        SoundService.teleportStart(player, spawnService.core());
        SoundService.teleportCountdown(player, spawnService.core());

        BukkitTask task = spawnService.core().getServer().getScheduler().runTaskTimer(
                spawnService.core(),
                () -> tick(player),
                20L,
                20L
        );

        pendingTeleports.put(player.getUniqueId(), new PendingTeleport(point, start, delay, task));
    }

    public void tick(Player player) {
        PendingTeleport pending = pendingTeleports.get(player.getUniqueId());

        if (pending == null) {
            return;
        }

        if (!player.isOnline()) {
            cancel(player, false);
            return;
        }

        if (spawnService.cancelOnMove() && movedTooFar(pending.startLocation(), player.getLocation())) {
            cancel(player, true);
            return;
        }

        int nextSeconds = pending.secondsRemaining() - 1;

        if (nextSeconds <= 0) {
            complete(player, pending.point());
            return;
        }

        pendingTeleports.put(
                player.getUniqueId(),
                new PendingTeleport(
                        pending.point(),
                        pending.startLocation(),
                        nextSeconds,
                        pending.task()
                )
        );

        String message = spawnService.message("teleport-countdown")
                .replace("%spawn%", TextColor.color(pending.point().displayName()))
                .replace("%seconds%", String.valueOf(nextSeconds));

        sendActionBar(player, message);
        SoundService.teleportCountdown(player, spawnService.core());
    }

    public void cancel(Player player, boolean sendMessage) {
        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());

        if (pending != null && pending.task() != null) {
            pending.task().cancel();
        }

        if (sendMessage) {
            sendActionBar(player, CANCELLED_MOVE_MESSAGE);
            SoundService.teleportCancelled(player, spawnService.core());
        }
    }

    private void complete(Player player, SpawnPoint point) {
        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());

        if (pending != null && pending.task() != null) {
            pending.task().cancel();
        }

        if (!spawnService.teleport(player, point)) {
            String message = spawnService.message("world-missing")
                    .replace("%world%", point.worldName())
                    .replace("%spawn%", TextColor.color(point.displayName()));

            sendActionBar(player, message);
            SoundService.guiError(player, spawnService.core());
            return;
        }

        String message = spawnService.message("teleported")
                .replace("%spawn%", TextColor.color(point.displayName()));

        sendActionBar(player, message);
        SoundService.teleportComplete(player, spawnService.core());
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

        return start.distanceSquared(current) > spawnService.cancelMoveDistance() * spawnService.cancelMoveDistance();
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    private record PendingTeleport(
            SpawnPoint point,
            Location startLocation,
            int secondsRemaining,
            BukkitTask task
    ) {
    }
}