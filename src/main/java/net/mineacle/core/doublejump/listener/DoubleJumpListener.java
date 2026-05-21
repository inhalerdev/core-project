package net.mineacle.core.doublejump.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DoubleJumpListener implements Listener {

    private final Core core;
    private final Map<UUID, Long> lastJumpMillis = new HashMap<>();
    private final Set<UUID> flyEnabled = new HashSet<>();

    public DoubleJumpListener(Core core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(core, () -> refresh(player), 2L);
        core.getServer().getScheduler().runTaskLater(core, () -> refresh(player), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        lastJumpMillis.remove(uuid);
        flyEnabled.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        if (!isFlyWorld(player.getWorld().getName())) {
            flyEnabled.remove(player.getUniqueId());
        }

        core.getServer().getScheduler().runTaskLater(core, () -> refresh(player), 1L);
        core.getServer().getScheduler().runTaskLater(core, () -> refresh(player), 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (!isFlyWorld(player.getWorld().getName())) {
                flyEnabled.remove(player.getUniqueId());
            }

            refresh(player);
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(core, () -> refresh(player), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (flyEnabled.contains(player.getUniqueId())) {
            refreshFly(player);
            return;
        }

        if (!enabledForDoubleJump(player)) {
            disablePlayer(player);
            return;
        }

        if (isGrounded(player)) {
            player.setAllowFlight(true);
            player.setFlying(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        if (flyEnabled.contains(player.getUniqueId())) {
            if (!canUseFly(player)) {
                flyEnabled.remove(player.getUniqueId());
                disablePlayer(player);
                event.setCancelled(true);
            }

            return;
        }

        if (!enabledForDoubleJump(player)) {
            disablePlayer(player);
            return;
        }

        event.setCancelled(true);
        player.setFlying(false);
        player.setAllowFlight(false);

        if (isOnCooldown(player)) {
            SoundService.play(player, core, "double-jump.cooldown");

            if (core.getConfig().getBoolean("double-jump.actionbar-cooldown", false)) {
                player.sendActionBar(actionBar("&cDouble jump is cooling down"));
            }

            core.getServer().getScheduler().runTaskLater(core, () -> refresh(player), 5L);
            return;
        }

        launch(player);
    }

    public boolean toggleFly(Player player) {
        UUID uuid = player.getUniqueId();

        if (flyEnabled.contains(uuid)) {
            flyEnabled.remove(uuid);
            refresh(player);
            return false;
        }

        flyEnabled.add(uuid);
        refreshFly(player);
        return true;
    }

    public boolean isFlyWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        for (String configuredWorld : core.getConfig().getStringList("fly.worlds")) {
            if (configuredWorld.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    public void refreshAll() {
        for (Player player : core.getServer().getOnlinePlayers()) {
            refresh(player);
        }
    }

    public void disableAll() {
        for (Player player : core.getServer().getOnlinePlayers()) {
            disablePlayer(player);
        }

        lastJumpMillis.clear();
        flyEnabled.clear();
    }

    private void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (flyEnabled.contains(player.getUniqueId())) {
            refreshFly(player);
            return;
        }

        if (!enabledForDoubleJump(player)) {
            disablePlayer(player);
            return;
        }

        /*
         * Important:
         * This must enable allowFlight whenever the player is grounded/effectively grounded.
         * Without this, PlayerToggleFlightEvent never fires and double jump feels broken.
         */
        if (isGrounded(player)) {
            player.setAllowFlight(true);
            player.setFlying(false);
        }
    }

    private void refreshFly(Player player) {
        if (!canUseFly(player)) {
            flyEnabled.remove(player.getUniqueId());
            disablePlayer(player);
            return;
        }

        player.setAllowFlight(true);
    }

    private boolean canUseFly(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        if (!core.getConfig().getBoolean("fly.enabled", true)) {
            return false;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }

        String permission = core.getConfig().getString("fly.permission", "mineacle.plus");

        if (!player.hasPermission(permission) && !player.hasPermission("mineaclefly.admin")) {
            return false;
        }

        return isFlyWorld(player.getWorld().getName());
    }

    private void launch(Player player) {
        UUID uuid = player.getUniqueId();

        lastJumpMillis.put(uuid, System.currentTimeMillis());

        double upward = core.getConfig().getDouble("double-jump.upward-velocity", 0.75D);
        double forward = core.getConfig().getDouble("double-jump.forward-velocity", 1.50D);

        Vector direction = player.getLocation().getDirection().clone();
        direction.setY(0.0D);

        if (direction.lengthSquared() > 0.0D) {
            direction.normalize().multiply(forward);
        }

        direction.setY(upward);
        player.setVelocity(direction);

        playParticles(player);
        SoundService.play(player, core, "double-jump.jump");

        core.getServer().getScheduler().runTaskLater(core, () -> refresh(player), 10L);
    }

    private boolean isOnCooldown(Player player) {
        double cooldownSeconds = Math.max(0.0D, core.getConfig().getDouble("double-jump.cooldown-seconds", 0.75D));

        if (cooldownSeconds <= 0.0D) {
            return false;
        }

        Long lastJump = lastJumpMillis.get(player.getUniqueId());

        if (lastJump == null) {
            return false;
        }

        long cooldownMillis = Math.round(cooldownSeconds * 1000.0D);
        return System.currentTimeMillis() - lastJump < cooldownMillis;
    }

    private boolean enabledForDoubleJump(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        if (!core.getConfig().getBoolean("double-jump.enabled", true)) {
            return false;
        }

        if (flyEnabled.contains(player.getUniqueId())) {
            return false;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        if (player.isInsideVehicle()) {
            return false;
        }

        World world = player.getWorld();

        if (world == null) {
            return false;
        }

        return isDoubleJumpWorld(world.getName());
    }

    private boolean isDoubleJumpWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        for (String configuredWorld : core.getConfig().getStringList("double-jump.worlds")) {
            if (configuredWorld.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    private boolean isGrounded(Player player) {
        if (player.isOnGround()) {
            return true;
        }

        Location location = player.getLocation();
        Block below = location.clone().subtract(0.0D, 0.08D, 0.0D).getBlock();

        return below.getType().isSolid();
    }

    private void disablePlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (player.isFlying()) {
            player.setFlying(false);
        }

        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
        }
    }

    private void playParticles(Player player) {
        if (!core.getConfig().getBoolean("double-jump.particles", true)) {
            return;
        }

        Location location = player.getLocation().clone().add(0.0D, 0.15D, 0.0D);

        try {
            player.getWorld().spawnParticle(
                    Particle.CLOUD,
                    location,
                    12,
                    0.35D,
                    0.05D,
                    0.35D,
                    0.02D
            );
        } catch (Exception ignored) {
        }
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
