package net.mineacle.core.warps.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.warps.model.WarpPoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WarpTeleportService {

    private final Core core;
    private final WarpService warpService;
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();

    public WarpTeleportService(Core core, WarpService warpService) {
        this.core = core;
        this.warpService = warpService;
    }

    public void teleport(Player player, WarpPoint point) {
        cancel(player, false);

        int delay = warpService.countdownSeconds(player);

        if (delay <= 0) {
            complete(player, point);
            return;
        }

        Location start = player.getLocation().clone();
        player.sendMessage(warpService.startingMessage(point.key(), delay));
        player.sendActionBar(net.kyori.adventure.text.Component.text(warpService.startingMessage(point.key(), delay)));

        BukkitRunnable task = new BukkitRunnable() {
            private int secondsLeft = delay;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel(player, false);
                    return;
                }

                if (moved(start, player.getLocation())) {
                    cancel(player, true);
                    return;
                }

                if (secondsLeft <= 0) {
                    tasks.remove(player.getUniqueId());
                    complete(player, point);
                    cancel();
                    return;
                }

                player.sendActionBar(net.kyori.adventure.text.Component.text(warpService.startingMessage(point.key(), secondsLeft)));
                SoundService.teleportTick(player, core);
                secondsLeft--;
            }
        };

        tasks.put(player.getUniqueId(), task);
        task.runTaskTimer(core, 0L, 20L);
    }

    public void cancel(Player player, boolean message) {
        BukkitRunnable existing = tasks.remove(player.getUniqueId());

        if (existing != null) {
            existing.cancel();
        }

        if (message) {
            player.sendMessage(warpService.cancelledMessage());
            player.sendActionBar(net.kyori.adventure.text.Component.text(warpService.cancelledMessage()));
            SoundService.teleportCancel(player, core);
        }
    }

    public void cancelAll() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }

        tasks.clear();
    }

    private void complete(Player player, WarpPoint point) {
        Location target = warpService.targetLocation(player, point);
        player.teleport(target);
        player.sendMessage(warpService.teleportMessage(point.key()));
        player.sendActionBar(net.kyori.adventure.text.Component.text(warpService.teleportMessage(point.key())));
        SoundService.teleportComplete(player, core);
    }

    private boolean moved(Location start, Location now) {
        if (start.getWorld() == null || now.getWorld() == null || !start.getWorld().equals(now.getWorld())) {
            return true;
        }

        return Math.abs(start.getX() - now.getX()) > 0.15D
                || Math.abs(start.getY() - now.getY()) > 0.15D
                || Math.abs(start.getZ() - now.getZ()) > 0.15D;
    }
}
