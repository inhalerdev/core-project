package net.mineacle.core.warps.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.model.WarpPoint;
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
import java.util.List;
import java.util.Locale;
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

    public Core core() {
        return core;
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

    private void ensureLoaded() {
        if (config == null) {
            reload();
        }
    }

    public List<WarpPoint> warps() {
        ensureLoaded();

        ConfigurationSection section = config.getConfigurationSection("warps");
        if (section == null) {
            return List.of();
        }

        List<WarpPoint> points = new ArrayList<>();

        for (String key : section.getKeys(false)) {
            WarpPoint point = warp(key);

            if (point != null) {
                points.add(point);
            }
        }

        points.sort((first, second) -> {
            int slotCompare = Integer.compare(first.slot(), second.slot());
            if (slotCompare != 0) {
                return slotCompare;
            }

            return first.key().compareToIgnoreCase(second.key());
        });

        return points;
    }

    public List<String> warpKeys(String partial) {
        String normalized = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        List<String> keys = new ArrayList<>();

        for (WarpPoint point : warps()) {
            if (point.key().toLowerCase(Locale.ROOT).startsWith(normalized)) {
                keys.add(point.key());
            }
        }

        return keys;
    }

    public WarpPoint warp(String input) {
        ensureLoaded();

        if (input == null || input.isBlank()) {
            return null;
        }

        String key = normalizeKey(input);
        String path = "warps." + key;

        if (!config.isConfigurationSection(path)) {
            return null;
        }

        String displayName = config.getString(path + ".display-name", "&d" + key);
        Material material = parseMaterial(config.getString(path + ".material", "ENDER_PEARL"));
        int slot = config.getInt(path + ".slot", 13);

        double x = config.getDouble(path + ".x", 0.5D);
        double y = config.getDouble(path + ".y", 65.0D);
        double z = config.getDouble(path + ".z", 0.5D);
        float yaw = (float) config.getDouble(path + ".yaw", 0.0D);
        float pitch = (float) config.getDouble(path + ".pitch", 0.0D);

        return new WarpPoint(key, displayName, material, slot, x, y, z, yaw, pitch);
    }

    public void setWarp(String keyInput, Player player, int slot, String displayName) throws IOException {
        ensureLoaded();

        String key = normalizeKey(keyInput);
        String path = "warps." + key;

        config.set(path + ".display-name", displayName == null || displayName.isBlank() ? "&d" + key : displayName);
        config.set(path + ".material", player.getInventory().getItemInMainHand().getType().isAir()
                ? "ENDER_PEARL"
                : player.getInventory().getItemInMainHand().getType().name());
        config.set(path + ".slot", slot);
        config.set(path + ".x", player.getLocation().getX());
        config.set(path + ".y", player.getLocation().getY());
        config.set(path + ".z", player.getLocation().getZ());
        config.set(path + ".yaw", player.getLocation().getYaw());
        config.set(path + ".pitch", player.getLocation().getPitch());

        config.save(file);
    }

    public boolean deleteWarp(String keyInput) throws IOException {
        ensureLoaded();

        String key = normalizeKey(keyInput);
        String path = "warps." + key;

        if (!config.isConfigurationSection(path)) {
            return false;
        }

        config.set(path, null);
        config.save(file);
        return true;
    }

    public int countdownSeconds(Player player) {
        ensureLoaded();

        if (player == null || isSpawnWorld(player.getWorld().getName())) {
            return 0;
        }

        if (player.hasPermission("mineacle.plus") || player.hasPermission("mineaclewarps.admin")) {
            return Math.max(0, config.getInt("teleport.plus-delay-seconds", 3));
        }

        return Math.max(0, config.getInt("teleport.default-delay-seconds", 5));
    }

    public Location targetLocation(Player player, WarpPoint point) {
        World world;

        if (player != null && isSpawnWorld(player.getWorld().getName())) {
            world = player.getWorld();
        } else {
            world = randomSpawnWorld();

            if (world == null && player != null) {
                world = player.getWorld();
            }
        }

        if (world == null) {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }

        if (world == null) {
            throw new IllegalStateException("No loaded world is available for warp " + point.key());
        }

        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    public boolean isSpawnWorld(String worldName) {
        if (worldName == null) {
            return false;
        }

        for (String spawnWorld : spawnWorlds()) {
            if (spawnWorld.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    private World randomSpawnWorld() {
        List<World> loaded = new ArrayList<>();

        for (String name : spawnWorlds()) {
            World world = Bukkit.getWorld(name);

            if (world != null) {
                loaded.add(world);
            }
        }

        if (loaded.isEmpty()) {
            return null;
        }

        return loaded.get(random.nextInt(loaded.size()));
    }

    private List<String> spawnWorlds() {
        ensureLoaded();

        List<String> worlds = config.getStringList("spawn-worlds");

        if (worlds.isEmpty()) {
            return List.of("spawn1", "spawn2", "spawn3");
        }

        return Collections.unmodifiableList(worlds);
    }

    public String noPermissionMessage() {
        return message("messages.no-permission", "&cYou do not have permission");
    }

    public String notFoundMessage(String warp) {
        return message("messages.not-found", "&cWarp not found").replace("%warp%", warp);
    }

    public String setMessage(String warp) {
        return message("messages.set", "&#bbbbbbWarp &d%warp% &#bbbbbbset").replace("%warp%", warp);
    }

    public String deletedMessage(String warp) {
        return message("messages.deleted", "&#bbbbbbWarp &d%warp% &#bbbbbbdeleted").replace("%warp%", warp);
    }

    public String teleportMessage(String warp) {
        return message("messages.teleported", "&#bbbbbbWarped to &d%warp%").replace("%warp%", warp);
    }

    public String startingMessage(String warp, int seconds) {
        return message("messages.starting", "&#bbbbbbWarping to &d%warp% &#bbbbbbin &d%seconds%s")
                .replace("%warp%", warp)
                .replace("%seconds%", String.valueOf(seconds));
    }

    public String cancelledMessage() {
        return message("messages.cancelled", "&cTeleport cancelled — you moved");
    }

    private String message(String path, String fallback) {
        ensureLoaded();
        return TextColor.color(config.getString(path, fallback));
    }

    private Material parseMaterial(String value) {
        if (value == null || value.isBlank()) {
            return Material.ENDER_PEARL;
        }

        try {
            Material material = Material.valueOf(value.toUpperCase(Locale.ROOT));
            return material.isAir() ? Material.ENDER_PEARL : material;
        } catch (IllegalArgumentException ignored) {
            return Material.ENDER_PEARL;
        }
    }

    private String normalizeKey(String input) {
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }
}
