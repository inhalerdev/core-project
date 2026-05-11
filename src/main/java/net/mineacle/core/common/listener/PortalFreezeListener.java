package net.mineacle.core.common.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PortalFreezeListener implements Listener {

    private static final Set<UUID> SKIP_NEXT_FREEZE = new HashSet<>();
    private static final Map<UUID, FrozenPlayer> FROZEN_PLAYERS = new HashMap<>();

    private final Core core;

    public PortalFreezeListener(Core core) {
        this.core = core;
    }

    public static void skipNextFreeze(Player player, Core core) {
        if (player == null) {
            return;
        }

        SKIP_NEXT_FREEZE.add(player.getUniqueId());

        if (core != null) {
            core.getServer().getScheduler().runTaskLater(core, () ->
                    SKIP_NEXT_FREEZE.remove(player.getUniqueId()), 20L
            );
        }
    }

    public static void clearFrozen(Player player) {
        if (player == null) {
            return;
        }

        SKIP_NEXT_FREEZE.remove(player.getUniqueId());
        FROZEN_PLAYERS.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null || to.getWorld() == null || from.getWorld() == null) {
            return;
        }

        if (SKIP_NEXT_FREEZE.remove(player.getUniqueId())) {
            clearFrozen(player);
            return;
        }

        if (!core.getConfig().getBoolean("portal-freeze.enabled", true)) {
            return;
        }

        if (!isPortalFreezeSourceWorld(from.getWorld())) {
            clearFrozen(player);
            return;
        }

        if (!isPortalLikeTeleport(event.getCause())) {
            clearFrozen(player);
            return;
        }

        freeze(player, to);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        FrozenPlayer frozen = FROZEN_PLAYERS.get(player.getUniqueId());

        if (frozen == null) {
            return;
        }

        if (System.currentTimeMillis() >= frozen.expiresAtMillis()) {
            FROZEN_PLAYERS.remove(player.getUniqueId());
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) {
            return;
        }

        if (sameBlockAndLookOnly(from, to)) {
            return;
        }

        Location locked = frozen.location().clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());

        event.setTo(locked);

        String actionbar = core.getConfig().getString("portal-freeze.actionbar", "&#ccccccLoading...");
        player.sendActionBar(actionBar(actionbar));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    public void clear(Player player) {
        clearFrozen(player);
    }

    private void freeze(Player player, Location location) {
        int durationTicks = Math.max(1, core.getConfig().getInt("portal-freeze.duration-ticks", 30));
        long expiresAt = System.currentTimeMillis() + (durationTicks * 50L);

        FROZEN_PLAYERS.put(player.getUniqueId(), new FrozenPlayer(location.clone(), expiresAt));

        String actionbar = core.getConfig().getString("portal-freeze.actionbar", "&#ccccccLoading...");
        player.sendActionBar(actionBar(actionbar));

        if (core.getConfig().getBoolean("portal-freeze.sound.enabled", true)) {
            String soundKey = core.getConfig().getString("portal-freeze.sound.key", "teleport.complete");
            SoundService.play(player, core, soundKey);
        }

        core.getServer().getScheduler().runTaskLater(core, () -> {
            FrozenPlayer frozen = FROZEN_PLAYERS.get(player.getUniqueId());

            if (frozen == null) {
                return;
            }

            if (System.currentTimeMillis() >= frozen.expiresAtMillis()) {
                FROZEN_PLAYERS.remove(player.getUniqueId());
            }
        }, durationTicks);
    }

    private boolean isFrozen(Player player) {
        FrozenPlayer frozen = FROZEN_PLAYERS.get(player.getUniqueId());

        if (frozen == null) {
            return false;
        }

        if (System.currentTimeMillis() >= frozen.expiresAtMillis()) {
            FROZEN_PLAYERS.remove(player.getUniqueId());
            return false;
        }

        return true;
    }

    private boolean isPortalFreezeSourceWorld(World world) {
        String worldName = world.getName();
        List<String> configuredWorlds = core.getConfig().getStringList("portal-freeze.source-worlds");

        if (configuredWorlds.isEmpty()) {
            return worldName.equalsIgnoreCase("spawn1")
                    || worldName.equalsIgnoreCase("spawn2")
                    || worldName.equalsIgnoreCase("spawn3");
        }

        for (String configuredWorld : configuredWorlds) {
            if (configuredWorld.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    private boolean isPortalLikeTeleport(PlayerTeleportEvent.TeleportCause cause) {
        return cause == PlayerTeleportEvent.TeleportCause.PLUGIN
                || cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL;
    }

    private boolean sameBlockAndLookOnly(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null) {
            return false;
        }

        if (!from.getWorld().equals(to.getWorld())) {
            return false;
        }

        return from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    private record FrozenPlayer(
            Location location,
            long expiresAtMillis
    ) {
    }
}
