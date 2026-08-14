package net.mineacle.core.doublejump.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DoubleJumpListener implements Listener {

    private static final long ACTIVE_FLY_GUARD_TICKS = 20L;

    private final Core core;

    private final Map<UUID, Long> lastJumpNanos = new HashMap<>();
    private final Set<UUID> doubleJumpFlightOwned = new HashSet<>();
    private final Set<UUID> flyEnabled = new HashSet<>();
    private final Map<UUID, FlightSnapshot> flySnapshots = new HashMap<>();

    private final BukkitTask activeFlyGuardTask;

    private Set<String> doubleJumpWorlds = Set.of();
    private Set<String> flyWorlds = Set.of();

    private long cooldownNanos;
    private double upwardVelocity;
    private double forwardVelocity;
    private boolean particles;
    private boolean cooldownActionBar;
    private boolean doubleJumpEnabled;
    private boolean flyFeatureEnabled;
    private String flyPermission = "mineacle.plus";

    public DoubleJumpListener(Core core) {
        this.core = core;
        reloadSettings();

        activeFlyGuardTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        this::validateActiveFlyers,
                        ACTIVE_FLY_GUARD_TICKS,
                        ACTIVE_FLY_GUARD_TICKS
                );
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> refresh(player),
                2L
        );
    }

    @SuppressWarnings("unused")
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

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        disableCoreFly(player, false);
        releaseDoubleJumpFlight(player);
        lastJumpNanos.remove(player.getUniqueId());
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> refresh(player),
                2L
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        lastJumpNanos.remove(player.getUniqueId());

        if (isFlyEnabled(player) && !canUseFly(player)) {
            disableCoreFly(player, true);
        }

        refresh(player);
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    if (isFlyEnabled(player) && !canUseFly(player)) {
                        disableCoreFly(player, true);
                    }

                    refresh(player);
                },
                1L
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    if (isFlyEnabled(player) && !canUseFly(player)) {
                        disableCoreFly(player, false);
                    }

                    refresh(player);
                },
                1L
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();

        /*
         * Rotation and horizontal movement make up the overwhelming majority
         * of movement events. Exit before even touching per-player state when
         * Y did not change.
         */
        if (Double.compare(
                event.getFrom().getY(),
                to.getY()
        ) == 0) {
            return;
        }

        Player player = event.getPlayer();

        /*
         * Core flight has one shared one-second permission/world guard. Do not
         * repeat permission/config checks on movement packets.
         */
        if (isFlyEnabled(player)) {
            return;
        }

        if (!isDoubleJumpEligible(player)) {
            releaseDoubleJumpFlight(player);
            return;
        }

        if (isSupported(player)) {
            armDoubleJump(player);
        }
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
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
                        cooldownActionBar()
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
        boolean ownedDoubleJump = doubleJumpFlightOwned.remove(playerId);
        boolean externalAllowFlight =
                player.getAllowFlight() && !ownedDoubleJump;
        boolean externalFlying =
                externalAllowFlight && player.isFlying();

        flySnapshots.put(
                playerId,
                new FlightSnapshot(
                        externalAllowFlight,
                        externalFlying
                )
        );
        flyEnabled.add(playerId);

        player.setAllowFlight(true);
        return true;
    }

    public void dropOutOfFly(Player player) {
        if (!isFlyEnabled(player)) {
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

        boolean permitted = player.hasPermission(flyPermission)
                || player.hasPermission("mineaclefly.admin");

        return permitted
                && isFlyWorld(player.getWorld().getName());
    }

    public void reloadSettingsAndRefresh() {
        reloadSettings();
        SoundService.clearCache();
        refreshAll();
    }

    public void refreshAll() {
        for (Player player : core.getServer().getOnlinePlayers()) {
            if (isFlyEnabled(player) && !canUseFly(player)) {
                disableCoreFly(player, true);
            }

            refresh(player);
        }
    }

    public void disableAll() {
        activeFlyGuardTask.cancel();

        for (Player player : new ArrayList<>(
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

    private void validateActiveFlyers() {
        if (flyEnabled.isEmpty()) {
            return;
        }

        List<Player> invalidPlayers = null;
        Iterator<UUID> iterator = flyEnabled.iterator();

        while (iterator.hasNext()) {
            UUID playerId = iterator.next();
            Player player = core.getServer().getPlayer(playerId);

            if (player == null || !player.isOnline()) {
                iterator.remove();
                flySnapshots.remove(playerId);
                continue;
            }

            if (!canUseFly(player)) {
                if (invalidPlayers == null) {
                    invalidPlayers = new ArrayList<>();
                }

                invalidPlayers.add(player);
            }
        }

        if (invalidPlayers == null) {
            return;
        }

        for (Player player : invalidPlayers) {
            disableCoreFly(player, true);
        }
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

        String configuredFlyPermission = core.getConfig().getString(
                "fly.permission"
        );
        flyPermission = configuredFlyPermission == null
                || configuredFlyPermission.isBlank()
                ? "mineacle.plus"
                : configuredFlyPermission.trim();

        double cooldownSeconds = finiteClamped(
                core.getConfig().getDouble(
                        "double-jump.cooldown-seconds",
                        0.75D
                ),
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
                4.0D,
                0.75D
        );
        forwardVelocity = finiteClamped(
                core.getConfig().getDouble(
                        "double-jump.forward-velocity",
                        1.50D
                ),
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

        if (!isSupported(player)) {
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

        boolean restoreExternalAllowFlight = snapshot != null
                && snapshot.externalAllowFlight();
        boolean restoreExternalFlying = restoreExternalAllowFlight
                && snapshot.externalFlying();

        player.setFlying(false);
        player.setAllowFlight(restoreExternalAllowFlight);

        if (restoreExternalFlying) {
            player.setFlying(true);
        }

        if (drop
                && !isSupported(player)
                && !restoreExternalAllowFlight) {
            Vector velocity = player.getVelocity();

            player.setVelocity(
                    new Vector(
                            velocity.getX(),
                            Math.min(
                                    velocity.getY(),
                                    -0.35D
                            ),
                            velocity.getZ()
                    )
            );
        }

        if (!restoreExternalAllowFlight
                && isSupported(player)
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

        Long lastJump = lastJumpNanos.get(player.getUniqueId());

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

    private Set<String> normalizedWorlds(List<String> configuredWorlds) {
        if (configuredWorlds == null || configuredWorlds.isEmpty()) {
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
            double maximum,
            double fallback
    ) {
        if (!Double.isFinite(value)) {
            return fallback;
        }

        return Math.clamp(
                value,
                0.0D,
                maximum
        );
    }

    private boolean isSupported(Player player) {
        return ((Entity) player).isOnGround();
    }

    private Component cooldownActionBar() {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(
                                "&cDouble jump is cooling down"
                        )
                );
    }

    private record FlightSnapshot(
            boolean externalAllowFlight,
            boolean externalFlying
    ) {
    }
}
