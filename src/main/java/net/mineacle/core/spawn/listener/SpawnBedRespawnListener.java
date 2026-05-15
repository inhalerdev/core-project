package net.mineacle.core.spawn.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.service.SpawnService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SpawnBedRespawnListener implements Listener {

    private final SpawnService spawnService;
    private final Map<UUID, Location> respawnLocationAtDeath = new HashMap<>();

    public SpawnBedRespawnListener(SpawnService spawnService) {
        this.spawnService = spawnService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();

        if (block == null || !isBed(block.getType())) {
            return;
        }

        Player player = event.getPlayer();
        Location bedLocation = block.getLocation().clone();

        spawnService.core().getServer().getScheduler().runTaskLater(spawnService.core(), () -> {
            if (!player.isOnline()) {
                return;
            }

            Location respawnLocation = player.getRespawnLocation();

            if (respawnLocation == null || !sameBed(bedLocation, respawnLocation)) {
                return;
            }

            sendBoth(
                    player,
                    config("spawn.bed-respawn.set-message", "&#ccccccRespawn point set"),
                    config("spawn.bed-respawn.set-actionbar", "&#ccccccRespawn point set")
            );
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location respawnLocation = player.getRespawnLocation();

        if (respawnLocation == null) {
            respawnLocationAtDeath.remove(player.getUniqueId());
            return;
        }

        respawnLocationAtDeath.put(player.getUniqueId(), respawnLocation.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location savedRespawn = respawnLocationAtDeath.remove(player.getUniqueId());

        if (savedRespawn == null) {
            return;
        }

        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }

        spawnService.core().getServer().getScheduler().runTaskLater(spawnService.core(), () -> {
            if (!player.isOnline()) {
                return;
            }

            sendBoth(
                    player,
                    config("spawn.bed-respawn.missing-message", "&cYour home bed was missing or obstructed"),
                    config("spawn.bed-respawn.missing-actionbar", "&cYour home bed was missing or obstructed")
            );
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedBreak(BlockBreakEvent event) {
        clearRespawnLocations(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            clearRespawnLocations(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            clearRespawnLocations(block);
        }
    }

    private void clearRespawnLocations(Block block) {
        if (block == null || !isBed(block.getType())) {
            return;
        }

        Location bedLocation = block.getLocation();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Location respawnLocation = player.getRespawnLocation();

            if (respawnLocation == null || !sameBed(bedLocation, respawnLocation)) {
                continue;
            }

            player.setRespawnLocation(null, true);
        }
    }

    private boolean sameBed(Location bedLocation, Location respawnLocation) {
        if (bedLocation == null || respawnLocation == null) {
            return false;
        }

        if (bedLocation.getWorld() == null || respawnLocation.getWorld() == null) {
            return false;
        }

        if (!bedLocation.getWorld().equals(respawnLocation.getWorld())) {
            return false;
        }

        return Math.abs(bedLocation.getBlockX() - respawnLocation.getBlockX()) <= 1
                && Math.abs(bedLocation.getBlockY() - respawnLocation.getBlockY()) <= 1
                && Math.abs(bedLocation.getBlockZ() - respawnLocation.getBlockZ()) <= 1;
    }

    private boolean isBed(Material material) {
        return material != null && material.name().endsWith("_BED");
    }

    private String config(String path, String fallback) {
        return spawnService.core().getConfig().getString(path, fallback);
    }

    private void sendBoth(Player player, String chat, String actionbar) {
        if (chat != null && !chat.isBlank()) {
            player.sendMessage(TextColor.color(chat));
        }

        if (actionbar != null && !actionbar.isBlank()) {
            player.sendActionBar(actionBar(actionbar));
        }
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
