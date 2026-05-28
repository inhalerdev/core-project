package net.mineacle.core.warps.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warp.model.WarpPoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

public final class WarpService {

    private final Core core;
    private final File file;
    private final Random random = new Random();

    private FileConfiguration config;

    public WarpService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "warps.yml");
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            core.saveResource("warps.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
    }

    private FileConfiguration config() {
        if (config == null) {
            reload();
        }

        return config;
    }

    public String noPermissionMessage() {
        return TextColor.color(config().getString("messages.no-permission", "&cYou do not have permission"));
    }

    public String notFoundMessage(String name) {
        return TextColor.color(config().getString("messages.not-found", "&cWarp not found").replace("%warp%", name));
    }

    public String setMessage(String name) {
        return TextColor.color(config().getString("messages.set", "&#bbbbbbWarp &d%warp% &#bbbbbbset").replace("%warp%", name));
    }

    public String deletedMessage(String name) {
        return TextColor.color(config().getString("messages.deleted", "&#bbbbbbWarp &d%warp% &#bbbbbbdeleted").replace("%warp%", name));
    }

    public String teleportMessage(String name) {
        return TextColor.color(config().getString("messages.teleported", "&#bbbbbbWarped to &d%warp%").replace("%warp%", name));
    }

    public String startingMessage(String name, int seconds) {
        return TextColor.color(config().getString("messages.starting", "&#bbbbbbWarping to &d%warp% &#bbbbbbin &d%seconds%s")
                .replace("%warp%", name)
                .replace("%seconds%", String.valueOf(seconds)));
    }

    public String cancelledMessage() {
        return TextColor.color(config().getString("messages.cancelled", "&cTeleport cancelled — you moved"));
    }

    public List<String> spawnWorlds() {
        List<String> worlds = config().getStringList("spawn-worlds");

        if (worlds.isEmpty()) {
            return List.of("spawn1", "spawn2", "spawn3");
        }

        return worlds;
    }

    public boolean isSpawnWorld(String worldName) {
        if (worldName == null) {
            return false;
        }

        for (String world : spawnWorlds()) {
            if (world.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    public int countdownSeconds(Player player) {
        if (isSpawnWorld(player.getWorld().getName())) {
            return 0;
        }

        if (player.isOp() || player.hasPermission("mineacle.plus") || player.hasPermission("mineaclewarps.admin")) {
            return config().getInt("teleport.plus-delay-seconds", 3);
        }

        return config().getInt("teleport.default-delay-seconds", 5);
    }

    public Location targetLocation(Player player, WarpPoint point) {
        World world;

        if (isSpawnWorld(player.getWorld().getName())) {
            world = player.getWorld();
        } else {
            world = randomLoadedSpawnWorld().orElse(player.getWorld());
        }

        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    public Optional<World> randomLoadedSpawnWorld() {
        List<World> loaded = new ArrayList<>();

        for (String worldName : spawnWorlds()) {
            World world = Bukkit.getWorld(worldName);

            if (world != null) {
                loaded.add(world);
            }
        }

        if (loaded.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(loaded.get(random.nextInt(loaded.size())));
    }

    public List<WarpPoint> warps() {
        ConfigurationSection section = config().getConfigurationSection("warps");

        if (section == null) {
            return Collections.emptyList();
        }

        List<WarpPoint> points = new ArrayList<>();

        for (String key : section.getKeys(false)) {
            WarpPoint point = warp(key);

            if (point != null) {
                points.add(point);
            }
        }

        points.sort(Comparator.comparingInt(WarpPoint::slot).thenComparing(WarpPoint::key));
        return points;
    }

    public WarpPoint warp(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String key = input.toLowerCase(Locale.ROOT);
        ConfigurationSection section = config().getConfigurationSection("warps." + key);

        if (section == null) {
            return null;
        }

        Material material = Material.matchMaterial(section.getString("material", "ENDER_PEARL"));

        if (material == null) {
            material = Material.ENDER_PEARL;
        }

        return new WarpPoint(
                key,
                section.getString("display-name", "&d" + key),
                material,
                section.getInt("slot", 13),
                section.getDouble("x", 0.5D),
                section.getDouble("y", 65.0D),
                section.getDouble("z", 0.5D),
                (float) section.getDouble("yaw", 0.0D),
                (float) section.getDouble("pitch", 0.0D)
        );
    }

    public List<String> warpKeys(String partial) {
        ConfigurationSection section = config().getConfigurationSection("warps");

        if (section == null) {
            return Collections.emptyList();
        }

        List<String> keys = new ArrayList<>(section.getKeys(false));
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    public void setWarp(String key, Player player, int slot, String displayName) throws IOException {
        String normalized = key.toLowerCase(Locale.ROOT);
        Location location = player.getLocation();
        String path = "warps." + normalized;

        config().set(path + ".display-name", displayName == null || displayName.isBlank() ? "&d" + normalized : displayName);
        config().set(path + ".material", "ENDER_PEARL");
        config().set(path + ".slot", slot);
        config().set(path + ".x", location.getX());
        config().set(path + ".y", location.getY());
        config().set(path + ".z", location.getZ());
        config().set(path + ".yaw", location.getYaw());
        config().set(path + ".pitch", location.getPitch());

        save();
    }

    public boolean deleteWarp(String key) throws IOException {
        String normalized = key.toLowerCase(Locale.ROOT);

        if (!config().contains("warps." + normalized)) {
            return false;
        }

        config().set("warps." + normalized, null);
        save();
        return true;
    }

    private void save() throws IOException {
        config().save(file);
    }
}
