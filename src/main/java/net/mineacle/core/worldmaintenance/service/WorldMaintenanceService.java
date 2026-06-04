package net.mineacle.core.worldmaintenance.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WorldMaintenanceService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    public WorldMaintenanceService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "worldmaintenance.yml");
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        if (config == null) {
            return;
        }

        try {
            config.save(file);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save worldmaintenance.yml");
            exception.printStackTrace();
        }
    }

    public int notifyIntervalSeconds() {
        return Math.max(3, config.getInt("notify-interval-seconds", 10));
    }

    public List<String> groups() {
        ConfigurationSection section = config.getConfigurationSection("groups");
        if (section == null) {
            return List.of();
        }

        List<String> groups = new ArrayList<>(section.getKeys(false));
        groups.sort(String.CASE_INSENSITIVE_ORDER);
        return groups;
    }

    public boolean groupExists(String group) {
        return config.isConfigurationSection(groupPath(group));
    }

    public boolean enabled(String group) {
        return config.getBoolean(groupPath(group) + ".enabled", false);
    }

    public void setEnabled(String group, boolean enabled) {
        config.set(groupPath(group) + ".enabled", enabled);
        save();
    }

    public String action(String group) {
        return config.getString(groupPath(group) + ".action", "notify").toLowerCase(Locale.ROOT);
    }

    public List<String> worlds(String group) {
        return config.getStringList(groupPath(group) + ".worlds");
    }

    public boolean worldInGroup(String group, String world) {
        for (String configured : worlds(group)) {
            if (configured.equalsIgnoreCase(world)) {
                return true;
            }
        }

        return false;
    }

    public String activeGroupForWorld(String world) {
        for (String group : groups()) {
            if (!enabled(group)) {
                continue;
            }

            if (worldInGroup(group, world)) {
                return group;
            }
        }

        return null;
    }

    public void applyGroupToOnlinePlayers(String group) {
        if (!enabled(group)) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!worldInGroup(group, player.getWorld().getName())) {
                continue;
            }

            apply(player, group);
        }
    }

    public void apply(Player player) {
        String group = activeGroupForWorld(player.getWorld().getName());
        if (group == null) {
            return;
        }

        apply(player, group);
    }

    public void tickOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String group = activeGroupForWorld(player.getWorld().getName());
            if (group == null) {
                continue;
            }

            String action = action(group);
            if (action.equals("notify") || action.equals("message")) {
                sendNotice(player, group, true);
            }
        }
    }

    public void apply(Player player, String group) {
        if (player.hasPermission(config.getString("bypass-permission", "mineacleworldmaintenance.bypass"))) {
            return;
        }

        String action = action(group);

        if (action.equals("redirect-spawn") || action.equals("redirect")) {
            Location location = spawnLocation();

            if (location != null && !player.getWorld().getName().equalsIgnoreCase(location.getWorld().getName())) {
                player.teleport(location);
            }

            sendNotice(player, group, false);
            return;
        }

        sendNotice(player, group, false);
    }

    public void sendNotice(Player player, String group, boolean actionBarOnly) {
        String chat = config.getString(groupPath(group) + ".message",
                "&#bbbbbbThis world is being worked on for &dQoL &#bbbbbbor &dSafety &#bbbbbbupdates");

        String actionbar = config.getString(groupPath(group) + ".actionbar",
                "&#bbbbbbQoL or safety updates are being applied");

        if (!actionBarOnly) {
            player.sendMessage(TextColor.color(chat));
        }

        player.sendActionBar(component(actionbar));
    }

    public String statusLine(String group) {
        String state = enabled(group) ? "&aenabled" : "&cdisabled";
        return TextColor.color("&d" + group + " &8- " + state + " &#bbbbbb(" + action(group) + ")");
    }

    private Location spawnLocation() {
        String worldName = config.getString("redirect-spawn.world", "spawn1");
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }

        if (world == null) {
            return null;
        }

        if (!config.getBoolean("redirect-spawn.use-exact-location", false)) {
            return world.getSpawnLocation();
        }

        double x = config.getDouble("redirect-spawn.x", world.getSpawnLocation().getX());
        double y = config.getDouble("redirect-spawn.y", world.getSpawnLocation().getY());
        double z = config.getDouble("redirect-spawn.z", world.getSpawnLocation().getZ());
        float yaw = (float) config.getDouble("redirect-spawn.yaw", 0.0D);
        float pitch = (float) config.getDouble("redirect-spawn.pitch", 0.0D);

        return new Location(world, x, y, z, yaw, pitch);
    }

    private Component component(String input) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(input));
    }

    private String groupPath(String group) {
        return "groups." + group.toLowerCase(Locale.ROOT);
    }
}
