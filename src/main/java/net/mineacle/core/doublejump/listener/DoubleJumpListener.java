package net.mineacle.core.doublejump.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DoubleJumpListener implements Listener {

    private final Core core;

    private final Map<UUID, Long> lastJumpNanos = new HashMap<>();
    private final Set<UUID> doubleJumpFlightOwned = new HashSet<>();
    private final Set<UUID> flyEnabled = new HashSet<>();
    private final Map<UUID, FlightSnapshot> flySnapshots =
            new HashMap<>();

    private Set<String> doubleJumpWorlds = Set.of();
    private Set<String> flyWorlds = Set.of();

    private long cooldownNanos;
    private double upwardVelocity;
    private double forwardVelocity;
    private boolean particles;
    private boolean cooldownActionBar;
    private boolean doubleJumpEnabled;
    private boolean flyFeatureEnabled;

    public DoubleJumpListener(Core core) {
        this.core = core;
        reloadSettings();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> refresh(player),
                2L
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        disableCoreFly(player, false);
        releaseDoubleJumpFlight(player);

        lastJumpNanos.remove(playerId);
        flySnapshots.remove(playerId);
        flyEnabled.remove(playerId);
        doubleJumpFlightOwned.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        lastJumpNanos.remove(player.getUniqueId());

        if (isFlyEnabled(player) && !canUseFly(player)) {
            disableCoreFly(player, true);
        }

        refresh(player);
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (isFlyEnabled(player)
                            && !canUseFly(player)) {
                        disableCoreFly(player, true);
                    }

                    refresh(player);
                },
                1L
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onGameModeChange(
            PlayerGameModeChangeEvent event
    ) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (isFlyEnabled(player)
                            && !canUseFly(player)) {
                        disableCoreFly(player, false);
                    }

                    refresh(player);
                },
                1L
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (!positionChanged(event)) {
            return;
        }

        Player player = event.getPlayer();

        if (isFlyEnabled(player)) {
            if (!canUseFly(player)) {
                disableCoreFly(player, true);
            }

            return;
        }

        refreshDoubleJump(player);
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (flyEnabled.contains(playerId)) {
            if (!canUseFly(player)) {
                event.setCancelled(true);
                disableCoreFly(player, true);
            }

            return;
        }

        /*
         * Ignore flight supplied by Creative, Spectator, or another plugin.
         * Double Jump only intercepts flight state it explicitly armed.
         */
        if (!doubleJumpFlightOwned.contains(playerId)) {
            return;
        }

        event.setCancelled(true);
        doubleJumpFlightOwned.remove(playerId);

        if (!isDoubleJumpEligible(player)) {
            clearOwnedAllowFlight(player);
            return;
        }

        player.setFlying(false);
        player.setAllowFlight(false);

        if (isOnCooldown(player)) {
            SoundService.doubleJumpCooldown(player, core);

            if (cooldownActionBar) {
                player.sendActionBar(
                        actionBar(
                                "&cDouble jump is cooling down"
                        )
                );
            }

            core.getServer().getScheduler().runTaskLater(
                    core,
                    () -> refresh(player),
                    5L
            );
            return;
        }

        launch(player);
    }

    public boolean toggleFly(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        if (isFlyEnabled(player)) {
            disableCoreFly(player, true);
            return false;
        }

        if (!canUseFly(player)) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        boolean ownedDoubleJump =
                doubleJumpFlightOwned.remove(playerId);
        boolean externalAllowFlight =
                player.getAllowFlight() && !ownedDoubleJump;

        flySnapshots.put(
                playerId,
                new FlightSnapshot(externalAllowFlight)
        );
        flyEnabled.add(playerId);

        player.setAllowFlight(true);
        return true;
    }

    public void dropOutOfFly(Player player) {
        if (player == null || !isFlyEnabled(player)) {
            return;
        }

        disableCoreFly(player, true);
    }

    public boolean isFlyEnabled(Player player) {
        return player != null
                && flyEnabled.contains(player.getUniqueId());
    }

    public boolean isFlyWorld(String worldName) {
        return worldName != null
                && flyWorlds.contains(normalizeWorld(worldName));
    }

    public boolean canUseFly(Player player) {
        if (player == null
                || !player.isOnline()
                || !flyFeatureEnabled) {
            return false;
        }

        String permission = core.getConfig().getString(
                "fly.permission",
                "mineacle.plus"
        );

        boolean permitted = player.hasPermission(
                permission == null || permission.isBlank()
                        ? "mineacle.plus"
                        : permission
        ) || player.hasPermission("mineaclefly.admin");

        return permitted && isFlyWorld(
                player.getWorld().getName()
        );
    }

    public void reloadSettingsAndRefresh() {
        reloadSettings();
        SoundService.clearCache();
        refreshAll();
    }

    public void refreshAll() {
        for (Player player
                : core.getServer().getOnlinePlayers()) {
            if (isFlyEnabled(player) && !canUseFly(player)) {
                disableCoreFly(player, true);
            }

            refresh(player);
        }
    }

    public void disableAll() {
        for (Player player
                : new ArrayList<>(
                core.getServer().getOnlinePlayers()
        )) {
            disableCoreFly(player, false);
            releaseDoubleJumpFlight(player);
        }

        lastJumpNanos.clear();
        doubleJumpFlightOwned.clear();
        flyEnabled.clear();
        flySnapshots.clear();
    }

    private void reloadSettings() {
        doubleJumpEnabled = core.getConfig().getBoolean(
                "double-jump.enabled",
                true
        );
        flyFeatureEnabled = core.getConfig().getBoolean(
                "fly.enabled",
                true
        );

        doubleJumpWorlds = normalizedWorlds(
                core.getConfig().getStringList(
                        "double-jump.worlds"
                )
        );
        flyWorlds = normalizedWorlds(
                core.getConfig().getStringList("fly.worlds")
        );

        double cooldownSeconds = finiteClamped(
                core.getConfig().getDouble(
                        "double-jump.cooldown-seconds",
                        0.75D
                ),
                0.0D,
                60.0D,
                0.75D
        );

        cooldownNanos = Math.round(
                cooldownSeconds * 1_000_000_000.0D
        );
        upwardVelocity = finiteClamped(
                core.getConfig().getDouble(
                        "double-jump.upward-velocity",
                        0.75D
                ),
                0.0D,
                4.0D,
                0.75D
        );
        forwardVelocity = finiteClamped(
                core.getConfig().getDouble(
                        "double-jump.forward-velocity",
                        1.50D
                ),
                0.0D,
                4.0D,
                1.50D
        );
        particles = core.getConfig().getBoolean(
                "double-jump.particles",
                false
        );
        cooldownActionBar = core.getConfig().getBoolean(
                "double-jump.actionbar-cooldown",
                false
        );
    }

    private void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (isFlyEnabled(player)) {
            if (canUseFly(player)) {
                player.setAllowFlight(true);
            } else {
                disableCoreFly(player, true);
            }

            return;
        }

        refreshDoubleJump(player);
    }

    private void refreshDoubleJump(Player player) {
        if (!isDoubleJumpEligible(player)) {
            releaseDoubleJumpFlight(player);
            return;
        }

        if (!player.isOnGround()) {
            return;
        }

        armDoubleJump(player);
    }

    private void armDoubleJump(Player player) {
        UUID playerId = player.getUniqueId();

        if (flyEnabled.contains(playerId)
                || doubleJumpFlightOwned.contains(playerId)
                || player.getAllowFlight()) {
            return;
        }

        player.setFlying(false);
        player.setAllowFlight(true);
        doubleJumpFlightOwned.add(playerId);
    }

    private void releaseDoubleJumpFlight(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (!doubleJumpFlightOwned.remove(playerId)) {
            return;
        }

        clearOwnedAllowFlight(player);
    }

    private void clearOwnedAllowFlight(Player player) {
        if (player == null
                || !player.isOnline()
                || isCreativeFlight(player)
                || isFlyEnabled(player)) {
            return;
        }

        if (player.isFlying()) {
            player.setFlying(false);
        }

        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
        }
    }

    private void disableCoreFly(
            Player player,
            boolean drop
    ) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (!flyEnabled.remove(playerId)) {
            return;
        }

        FlightSnapshot snapshot = flySnapshots.remove(playerId);

        if (!player.isOnline() || isCreativeFlight(player)) {
            return;
        }

        player.setFlying(false);

        boolean restoreExternal = snapshot != null
                && snapshot.externalAllowFlight();

        player.setAllowFlight(restoreExternal);

        if (drop
                && !player.isOnGround()
                && !restoreExternal) {
            Vector velocity = player.getVelocity();

            player.setVelocity(new Vector(
                    velocity.getX(),
                    Math.min(velocity.getY(), -0.35D),
                    velocity.getZ()
            ));
        }

        if (!restoreExternal
                && player.isOnGround()
                && isDoubleJumpEligible(player)) {
            armDoubleJump(player);
        }
    }

    private void launch(Player player) {
        lastJumpNanos.put(
                player.getUniqueId(),
                System.nanoTime()
        );

        Vector direction = player.getLocation()
                .getDirection()
                .clone();
        direction.setY(0.0D);

        if (direction.lengthSquared() > 0.000001D) {
            direction.normalize().multiply(forwardVelocity);
        } else {
            direction.zero();
        }

        direction.setY(upwardVelocity);
        player.setVelocity(direction);

        playParticles(player);
        SoundService.doubleJump(player, core);
    }

    private boolean isOnCooldown(Player player) {
        if (cooldownNanos <= 0L) {
            return false;
        }

        Long lastJump = lastJumpNanos.get(
                player.getUniqueId()
        );

        return lastJump != null
                && System.nanoTime() - lastJump < cooldownNanos;
    }

    private boolean isDoubleJumpEligible(Player player) {
        if (player == null
                || !player.isOnline()
                || !doubleJumpEnabled
                || isFlyEnabled(player)
                || isCreativeFlight(player)
                || player.isInsideVehicle()
                || player.isGliding()
                || player.isRiptiding()
                || player.isDead()) {
            return false;
        }

        return doubleJumpWorlds.contains(
                normalizeWorld(player.getWorld().getName())
        );
    }

    private boolean isCreativeFlight(Player player) {
        GameMode gameMode = player.getGameMode();

        return gameMode == GameMode.CREATIVE
                || gameMode == GameMode.SPECTATOR;
    }

    private void playParticles(Player player) {
        if (!particles) {
            return;
        }

        Location location = player.getLocation()
                .clone()
                .add(0.0D, 0.15D, 0.0D);

        player.getWorld().spawnParticle(
                Particle.CLOUD,
                location,
                12,
                0.35D,
                0.05D,
                0.35D,
                0.02D
        );
    }

    private boolean positionChanged(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        return to != null
                && (from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ());
    }

    private Set<String> normalizedWorlds(
            List<String> configuredWorlds
    ) {
        if (configuredWorlds == null
                || configuredWorlds.isEmpty()) {
            return Set.of();
        }

        Set<String> worlds = new HashSet<>();

        for (String world : configuredWorlds) {
            if (world != null && !world.isBlank()) {
                worlds.add(normalizeWorld(world));
            }
        }

        return Set.copyOf(worlds);
    }

    private String normalizeWorld(String worldName) {
        return worldName.trim().toLowerCase(Locale.ROOT);
    }

    private double finiteClamped(
            double value,
            double minimum,
            double maximum,
            double fallback
    ) {
        if (!Double.isFinite(value)) {
            return fallback;
        }

        return Math.max(minimum, Math.min(maximum, value));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }

    private record FlightSnapshot(
            boolean externalAllowFlight
    ) {
    }
}
