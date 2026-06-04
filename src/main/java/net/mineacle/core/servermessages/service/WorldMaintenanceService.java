package net.mineacle.core.servermessages.service;

import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class WorldMaintenanceService {

    private final Core core;
    private final ServerMessageService messages;

    public WorldMaintenanceService(Core core, ServerMessageService messages) {
        this.core = core;
        this.messages = messages;
    }

    public void reload() {
        messages.reload();
    }

    public void save() {
        messages.save();
    }

    public int notifyIntervalSeconds() {
        return Math.max(3, messages.config().getInt("world-maintenance.notify-interval-seconds", 10));
    }

    public List<String> groups() {
        ConfigurationSection section = messages.config().getConfigurationSection("world-maintenance.groups");
        if (section == null) {
            return List.of();
        }

        List<String> groups = new ArrayList<>(section.getKeys(false));
        groups.sort(String.CASE_INSENSITIVE_ORDER);
        return groups;
    }

    public boolean groupExists(String group) {
        return messages.config().isConfigurationSection(groupPath(group));
    }

    public boolean enabled(String group) {
        return messages.config().getBoolean(groupPath(group) + ".enabled", false);
    }

    public void setEnabled(String group, boolean enabled) {
        messages.config().set(groupPath(group) + ".enabled", enabled);
        messages.save();
    }

    public String action(String group) {
        return messages.config().getString(groupPath(group) + ".action", "notify").toLowerCase(Locale.ROOT);
    }

    public List<String> worlds(String group) {
        return messages.config().getStringList(groupPath(group) + ".worlds");
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
        if (player.hasPermission(messages.config().getString("world-maintenance.bypass-permission", "mineacleservermessages.bypass"))) {
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
        String chat = format(messages.config().getString("world-maintenance.groups." + group + ".message",
                "&#bbbbbbThis world is being worked on for QoL or safety updates"));

        String actionbar = format(messages.config().getString("world-maintenance.groups." + group + ".actionbar",
                "&#bbbbbbQoL or safety updates are being applied"));

        if (!actionBarOnly) {
            player.sendMessage(TextColor.color(chat));
        }

        player.sendActionBar(Component.text(TextColor.strip(actionbar)));
    }

    public String statusLine(String group) {
        String state = enabled(group) ? "&aenabled" : "&cdisabled";
        return TextColor.color("&d" + group + " &8- " + state + " &#bbbbbb(" + action(group) + ")");
    }

    private Location spawnLocation() {
        String worldName = messages.config().getString("world-maintenance.redirect-spawn.world", "spawn1");
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }

        if (world == null) {
            return null;
        }

        if (!messages.config().getBoolean("world-maintenance.redirect-spawn.use-exact-location", false)) {
            return world.getSpawnLocation();
        }

        double x = messages.config().getDouble("world-maintenance.redirect-spawn.x", world.getSpawnLocation().getX());
        double y = messages.config().getDouble("world-maintenance.redirect-spawn.y", world.getSpawnLocation().getY());
        double z = messages.config().getDouble("world-maintenance.redirect-spawn.z", world.getSpawnLocation().getZ());
        float yaw = (float) messages.config().getDouble("world-maintenance.redirect-spawn.yaw", 0.0D);
        float pitch = (float) messages.config().getDouble("world-maintenance.redirect-spawn.pitch", 0.0D);

        return new Location(world, x, y, z, yaw, pitch);
    }

    private String groupPath(String group) {
        return "world-maintenance.groups." + group.toLowerCase(Locale.ROOT);
    }

    private String format(String value) {
        return value == null ? "" : value;
    }
}
