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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WarpService {

    private final Core core;
    private File warpFile;
    private FileConfiguration config;

    public WarpService(Core core) {
        this.core = core;
        load();
    }

    public Core core() {
        return core;
    }

    public void load() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        warpFile = new File(core.getDataFolder(), "warps.yml");

        if (!warpFile.exists()) {
            core.saveResource("warps.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(warpFile);
    }

    public void save() {
        if (warpFile == null || config == null) {
            return;
        }

        try {
            config.save(warpFile);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save warps.yml");
            exception.printStackTrace();
        }
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public String title() {
        return TextColor.strip(config.getString("title", "Warps"));
    }

    public int size() {
        int size = config.getInt("size", 54);
        if (size < 9) {
            return 54;
        }
        if (size > 54) {
            return 54;
        }
        return ((size + 8) / 9) * 9;
    }

    public boolean instantFromWorld(String worldName) {
        if (worldName == null) {
            return false;
        }

        for (String world : config.getStringList("teleport.instant-worlds")) {
            if (world.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    public int delaySeconds(Player player) {
        int defaultDelay = Math.max(0, config.getInt("teleport.delay-seconds", 5));
        int plusDelay = Math.max(0, config.getInt("teleport.plus-delay-seconds", 3));

        if (player == null) {
            return defaultDelay;
        }

        if (hasAnyPermission(player, config.getStringList("teleport.fast-permissions"))) {
            return plusDelay;
        }

        return defaultDelay;
    }

    public boolean cancelOnMove() {
        return config.getBoolean("teleport.cancel-on-move", true);
    }

    public double cancelDistance() {
        return Math.max(0.01D, config.getDouble("teleport.cancel-distance", 2.0D));
    }

    public String message(String path) {
        return TextColor.color(config.getString("messages." + path, "&cMissing warp message: " + path));
    }

    public List<WarpPoint> warps() {
        List<WarpPoint> points = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("warps");

        if (section == null) {
            return points;
        }

        for (String id : section.getKeys(false)) {
            String path = "warps." + id;

            String worldName = config.getString(path + ".world", "");
            World world = Bukkit.getWorld(worldName);

            if (worldName.isBlank()) {
                continue;
            }

            boolean enabled = config.getBoolean(path + ".enabled", true);
            int slot = config.getInt(path + ".slot", 0);
            String displayName = config.getString(path + ".display-name", "&d" + prettyName(id));
            double x = config.getDouble(path + ".x", world == null ? 0.0D : world.getSpawnLocation().getX());
            double y = config.getDouble(path + ".y", world == null ? 64.0D : world.getSpawnLocation().getY());
            double z = config.getDouble(path + ".z", world == null ? 0.0D : world.getSpawnLocation().getZ());
            float yaw = (float) config.getDouble(path + ".yaw", 0.0D);
            float pitch = (float) config.getDouble(path + ".pitch", 0.0D);
            Material material = material(config.getString(path + ".material", "ENDER_PEARL"));
            List<String> lore = config.getStringList(path + ".lore");

            points.add(new WarpPoint(
                    id,
                    displayName,
                    worldName,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    slot,
                    material,
                    enabled,
                    lore
            ));
        }

        points.sort(Comparator.comparingInt(WarpPoint::slot));
        return points;
    }

    public List<WarpPoint> availableWarps() {
        List<WarpPoint> points = new ArrayList<>();

        for (WarpPoint point : warps()) {
            if (!point.enabled()) {
                continue;
            }
            if (Bukkit.getWorld(point.worldName()) == null) {
                continue;
            }
            points.add(point);
        }

        return points;
    }

    public WarpPoint warpById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }

        for (WarpPoint point : warps()) {
            if (point.id().equalsIgnoreCase(id)) {
                return point;
            }
        }

        return null;
    }

    public WarpPoint warpBySlot(int slot) {
        for (WarpPoint point : availableWarps()) {
            if (point.slot() == slot) {
                return point;
            }
        }

        return null;
    }

    public Location location(WarpPoint point) {
        if (point == null) {
            return null;
        }

        World world = Bukkit.getWorld(point.worldName());

        if (world == null) {
            return null;
        }

        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    public boolean teleport(Player player, WarpPoint point) {
        Location location = location(point);

        if (location == null) {
            return false;
        }

        player.teleport(location);
        return true;
    }

    public boolean addWarp(Player player, String id, Integer slot, String displayName) {
        if (player == null || id == null || !isValidId(id)) {
            return false;
        }

        String normalized = id.toLowerCase(Locale.ROOT);
        Location location = player.getLocation();
        int finalSlot = slot == null ? nextOpenSlot() : slot;

        if (finalSlot < 0 || finalSlot >= size()) {
            return false;
        }

        String path = "warps." + normalized;
        config.set(path + ".enabled", true);
        config.set(path + ".slot", finalSlot);
        config.set(path + ".display-name", displayName == null || displayName.isBlank() ? "&d" + prettyName(normalized) : displayName);
        config.set(path + ".material", "ENDER_PEARL");
        config.set(path + ".world", location.getWorld().getName());
        config.set(path + ".x", round(location.getX()));
        config.set(path + ".y", round(location.getY()));
        config.set(path + ".z", round(location.getZ()));
        config.set(path + ".yaw", round(location.getYaw()));
        config.set(path + ".pitch", round(location.getPitch()));
        config.set(path + ".lore", config.getStringList("gui.default-lore"));

        save();
        load();
        return true;
    }

    public boolean removeWarp(String id) {
        WarpPoint point = warpById(id);

        if (point == null) {
            return false;
        }

        config.set("warps." + point.id(), null);
        save();
        load();
        return true;
    }

    public List<String> warpIds() {
        List<String> ids = new ArrayList<>();

        for (WarpPoint point : warps()) {
            ids.add(point.id());
        }

        return ids;
    }

    public List<String> lore(WarpPoint point, Player player) {
        List<String> source = point.lore().isEmpty() ? config.getStringList("gui.default-lore") : point.lore();
        List<String> output = new ArrayList<>();
        String mode = instantFromWorld(player.getWorld().getName()) ? "Instant" : delaySeconds(player) + "s";

        for (String line : source) {
            output.add(line
                    .replace("%warp%", point.displayName())
                    .replace("%id%", point.id())
                    .replace("%world%", point.worldName())
                    .replace("%mode%", mode)
            );
        }

        return output;
    }

    private boolean hasAnyPermission(Player player, List<String> permissions) {
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                continue;
            }
            if (player.hasPermission(permission)) {
                return true;
            }
        }

        return false;
    }

    private Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.ENDER_PEARL;
        }

        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Material.ENDER_PEARL;
        }
    }

    private int nextOpenSlot() {
        Set<Integer> used = new HashSet<>();

        for (WarpPoint point : warps()) {
            used.add(point.slot());
        }

        for (int slot = 0; slot < Math.min(45, size()); slot++) {
            if (!used.contains(slot)) {
                return slot;
            }
        }

        return 0;
    }

    private boolean isValidId(String id) {
        return id.matches("[A-Za-z0-9_-]{2,32}");
    }

    private String prettyName(String id) {
        String[] parts = id.replace('-', '_').split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }

        return builder.isEmpty() ? id : builder.toString();
    }

    private double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
