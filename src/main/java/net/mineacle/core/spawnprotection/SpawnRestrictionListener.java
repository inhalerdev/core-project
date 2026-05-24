package net.mineacle.core.spawnprotection.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SpawnRestrictionListener implements Listener {

    private final Core core;

    public SpawnRestrictionListener(Core core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameEvent(BlockReceiveGameEvent event) {
        Block block = event.getBlock();

        if (block == null || !isRestrictedWorld(block.getWorld())) {
            return;
        }

        if (blockedGameEvents().contains(block.getType()) || isDeepDarkBlock(block.getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();

        if (block == null || !isRestrictedWorld(block.getWorld())) {
            return;
        }

        if (blockedInteractions().contains(block.getType()) || isDeepDarkBlock(block.getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isRestrictedWorld(event.getBlockPlaced().getWorld())) {
            return;
        }

        if (blockedPlacement().contains(event.getBlockPlaced().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(TextColor.color("&cYou cannot place that here"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isRestrictedWorld(event.getBlock().getWorld())) {
            return;
        }

        if (blockedBreaking().contains(event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(TextColor.color("&cYou cannot break that here"));
        }
    }

    private boolean isRestrictedWorld(World world) {
        if (world == null) {
            return false;
        }

        List<String> worlds = core.getConfig().getStringList("spawn-restrictions.worlds");

        if (worlds.isEmpty()) {
            worlds = List.of("spawn1", "spawn2", "spawn3");
        }

        for (String name : worlds) {
            if (world.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }

        return false;
    }

    private Set<Material> blockedInteractions() {
        return materials("spawn-restrictions.blocked-interactions");
    }

    private Set<Material> blockedPlacement() {
        return materials("spawn-restrictions.blocked-placement");
    }

    private Set<Material> blockedBreaking() {
        return materials("spawn-restrictions.blocked-breaking");
    }

    private Set<Material> blockedGameEvents() {
        return materials("spawn-restrictions.blocked-game-events");
    }

    private Set<Material> materials(String path) {
        Set<Material> materials = new HashSet<>();

        for (String raw : core.getConfig().getStringList(path)) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            try {
                materials.add(Material.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return materials;
    }

    private boolean isDeepDarkBlock(Material material) {
        return material == Material.SCULK_SHRIEKER
                || material == Material.SCULK_SENSOR
                || material == Material.CALIBRATED_SCULK_SENSOR
                || material == Material.SCULK_CATALYST;
    }
}
