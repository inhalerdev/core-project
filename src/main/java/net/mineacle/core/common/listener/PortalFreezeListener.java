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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PortalFreezeListener implements Listener {

    private static final Set<UUID> SKIP_NEXT_FREEZE = new HashSet<>();

    private final Core core;
    private final Map<UUID, FrozenPlayer> frozenPlayers = new HashMap<>();

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();

        if (to == null || to.getWorld() == null) {
            return;
        }

        if (SKIP_NEXT_FREEZE.remove(player.getUniqueId())) {
            clear(player);
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) {
            clear(player);
            return;
        }

        if (!core.getConfig().getBoolean("portal-freeze.enabled", true)) {
            return;
        }

        if (!isFreezeWorld(to.getWorld())) {
            return;
        }

        freeze(player, to);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        FrozenPlayer frozen = frozenPlayers.get(player.getUniqueId());

        if (frozen == null) {
            return;
        }

        if (System.currentTimeMillis() >= frozen.expiresAtMillis()) {
            frozenPlayers.remove(player.getUniqueId());
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
        if (player == null) {
            return;
        }

        frozenPlayers.remove(player.getUniqueId());
    }

    private void freeze(Player player, Location location) {
        int durationTicks = Math.max(1, core.getConfig().getInt("portal-freeze.duration-ticks", 30));
        long expiresAt = System.currentTimeMillis() + (durationTicks * 50L);

        Location lockedLocation = location.clone();

        frozenPlayers.put(player.getUniqueId(), new FrozenPlayer(lockedLocation, expiresAt));

        String actionbar = core.getConfig().getString("portal-freeze.actionbar", "&#ccccccLoading...");
        player.sendActionBar(actionBar(actionbar));

        if (core.getConfig().getBoolean("portal-freeze.sound.enabled", true)) {
            String soundKey = core.getConfig().getString("portal-freeze.sound.key", "teleport.complete");
            SoundService.play(player, core, soundKey);
        }

        core.getServer().getScheduler().runTaskLater(core, () -> {
            FrozenPlayer frozen = frozenPlayers.get(player.getUniqueId());

            if (frozen == null) {
                return;
            }

            if (System.currentTimeMillis() >= frozen.expiresAtMillis()) {
                frozenPlayers.remove(player.getUniqueId());
            }
        }, durationTicks);
    }

    private boolean isFrozen(Player player) {
        FrozenPlayer frozen = frozenPlayers.get(player.getUniqueId());

        if (frozen == null) {
            return false;
        }

        if (System.currentTimeMillis() >= frozen.expiresAtMillis()) {
            frozenPlayers.remove(player.getUniqueId());
            return false;
        }

        return true;
    }

    private boolean isFreezeWorld(World world) {
        String worldName = world.getName();

        for (String configuredWorld : core.getConfig().getStringList("portal-freeze.worlds")) {
            if (configuredWorld.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
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