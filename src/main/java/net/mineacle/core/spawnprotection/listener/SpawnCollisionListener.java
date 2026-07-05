package net.mineacle.core.spawnprotection.listener;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SpawnCollisionListener implements Listener {

    private final Core core;

    public SpawnCollisionListener(Core core) {
        this.core = core;
    }

    public void applyToOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    public void restoreOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setCollidable(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleApply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        event.getPlayer().setCollidable(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        scheduleApply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleApply(event.getPlayer());
    }

    private void scheduleApply(Player player) {
        Bukkit.getScheduler().runTask(core, () -> {
            if (player.isOnline()) {
                apply(player);
            }
        });
    }

    private void apply(Player player) {
        if (!enabled()) {
            player.setCollidable(true);
            return;
        }

        player.setCollidable(!isNoCollisionWorld(player.getWorld()));
    }

    private boolean enabled() {
        return core.getConfig().getBoolean("spawn-protection.collision.enabled", true);
    }

    private boolean isNoCollisionWorld(World world) {
        if (world == null) {
            return false;
        }

        return worlds().contains(world.getName().toLowerCase(Locale.ROOT));
    }

    private Set<String> worlds() {
        FileConfiguration config = core.getConfig();
        Set<String> worlds = new HashSet<>();

        for (String world : config.getStringList("spawn-protection.collision.worlds")) {
            if (world != null && !world.isBlank()) {
                worlds.add(world.toLowerCase(Locale.ROOT));
            }
        }

        if (worlds.isEmpty()) {
            worlds.add("spawn1");
            worlds.add("spawn2");
            worlds.add("spawn3");
        }

        return worlds;
    }
}
