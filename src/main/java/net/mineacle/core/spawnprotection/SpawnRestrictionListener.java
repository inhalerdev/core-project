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

        Material type = block.getType();

        if (blockedInteractions().contains(type) || defaultBlockedInteractions().contains(type) || isDeepDarkBlock(type)) {
            event.setCancelled(true);

            if (core.getConfig().getBoolean("spawn-restrictions.show-blocked-message", false)) {
                event.getPlayer().sendMessage(TextColor.color(
                        core.getConfig().getString("spawn-restrictions.messages.blocked-interaction", "&cYou cannot use that here")
                ));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isRestrictedWorld(event.getBlockPlaced().getWorld())) {
            return;
        }

        if (blockedPlacement().contains(event.getBlockPlaced().getType())) {
            event.setCancelled(true);

            if (core.getConfig().getBoolean("spawn-restrictions.show-blocked-message", false)) {
                event.getPlayer().sendMessage(TextColor.color(
                        core.getConfig().getString("spawn-restrictions.messages.blocked-place", "&cYou cannot place that here")
                ));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isRestrictedWorld(event.getBlock().getWorld())) {
            return;
        }

        if (blockedBreaking().contains(event.getBlock().getType())) {
            event.setCancelled(true);

            if (core.getConfig().getBoolean("spawn-restrictions.show-blocked-message", false)) {
                event.getPlayer().sendMessage(TextColor.color(
                        core.getConfig().getString("spawn-restrictions.messages.blocked-break", "&cYou cannot break that here")
                ));
            }
        }
    }

    private boolean isRestrictedWorld(World world) {
        if (world == null || !core.getConfig().getBoolean("spawn-restrictions.enabled", true)) {
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

            Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));

            if (material != null) {
                materials.add(material);
            }
        }

        return materials;
    }

    private Set<Material> defaultBlockedInteractions() {
        Set<Material> materials = new HashSet<>();

        add(materials,
                "ENDER_CHEST",
                "CRAFTING_TABLE",
                "SMITHING_TABLE",
                "STONECUTTER",
                "ANVIL",
                "CHIPPED_ANVIL",
                "DAMAGED_ANVIL",
                "GRINDSTONE",
                "CARTOGRAPHY_TABLE",
                "LOOM",
                "FLETCHING_TABLE",
                "ENCHANTING_TABLE",
                "BREWING_STAND",
                "FURNACE",
                "BLAST_FURNACE",
                "SMOKER",
                "CHEST",
                "TRAPPED_CHEST",
                "BARREL",
                "HOPPER",
                "DISPENSER",
                "DROPPER",
                "LECTERN",
                "BEACON",
                "RESPAWN_ANCHOR",
                "JUKEBOX",
                "NOTE_BLOCK",
                "DECORATED_POT"
        );

        for (Material material : Material.values()) {
            String name = material.name();

            if (name.endsWith("_SHULKER_BOX")) {
                materials.add(material);
            }
        }

        return materials;
    }

    private void add(Set<Material> materials, String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);

            if (material != null) {
                materials.add(material);
            }
        }
    }

    private boolean isDeepDarkBlock(Material material) {
        return material == Material.SCULK_SHRIEKER
                || material == Material.SCULK_SENSOR
                || material == Material.CALIBRATED_SCULK_SENSOR
                || material == Material.SCULK_CATALYST;
    }
}
