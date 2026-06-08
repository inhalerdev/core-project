package net.mineacle.core.nametag;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.hide.HideModule;
import net.mineacle.core.hide.HideService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.util.Transformation;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class NametagService {

    private final Core core;
    private final File file;
    private FileConfiguration config;
    private final Map<UUID, UUID> displays = new HashMap<>();

    public NametagService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "nametags.yml");
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            core.saveResource("nametags.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public long updateIntervalSeconds() {
        return Math.max(1L, config.getLong("update-interval-seconds", 2L));
    }

    public boolean enabledInWorld(Player player) {
        if (player == null) {
            return false;
        }

        if (!config.getBoolean("worlds.enabled", true)) {
            return true;
        }

        List<String> worlds = config.getStringList("worlds.list");

        if (worlds.isEmpty()) {
            return true;
        }

        return worlds.stream().anyMatch(world -> world.equalsIgnoreCase(player.getWorld().getName()));
    }

    public void refreshAll() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!enabled() || !enabledInWorld(player)) {
                removeDisplay(player);
                removeFromMineacleTeams(player, scoreboard);
                continue;
            }

            refresh(player, scoreboard);
        }
    }

    public void refresh(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        if (!enabled() || !enabledInWorld(player)) {
            removeDisplay(player);
            removeFromMineacleTeams(player, scoreboard);
            return;
        }

        refresh(player, scoreboard);
    }

    private void refresh(Player player, Scoreboard scoreboard) {
        String teamName = teamName(player);
        Team team = scoreboard.getTeam(teamName);

        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }

        removeFromOtherMineacleTeams(player, scoreboard, teamName);

        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        HideService hideService = HideModule.service();

        if (hideService != null && hideService.shouldHideRealNametag(player)) {
            team.prefix(Component.empty());
            team.suffix(Component.empty());
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            removeDisplay(player);
            return;
        }

        team.prefix(Component.empty());
        team.suffix(Component.empty());
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        updateDisplay(player);
    }

    private void updateDisplay(Player player) {
        TextDisplay display = display(player);

        if (display == null) {
            display = createDisplay(player);
        }

        if (display == null) {
            return;
        }

        display.text(legacy(DisplayNames.prefixedDisplayName(player)));

        if (!display.getPassengers().isEmpty()) {
            display.eject();
        }

        if (!player.getPassengers().contains(display)) {
            player.addPassenger(display);
        }
    }

    private TextDisplay display(Player player) {
        UUID id = displays.get(player.getUniqueId());

        if (id == null) {
            return null;
        }

        Entity entity = Bukkit.getEntity(id);

        if (!(entity instanceof TextDisplay display) || !display.isValid()) {
            displays.remove(player.getUniqueId());
            return null;
        }

        return display;
    }

    private TextDisplay createDisplay(Player player) {
        Location location = player.getLocation().clone();

        TextDisplay display = player.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setLineWidth(160);
            entity.setViewRange(32.0F);
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0F, 0.72F, 0.0F),
                    new Quaternionf(),
                    new Vector3f(1.0F, 1.0F, 1.0F),
                    new Quaternionf()
            ));
            entity.text(legacy(DisplayNames.prefixedDisplayName(player)));
        });

        displays.put(player.getUniqueId(), display.getUniqueId());
        player.addPassenger(display);
        return display;
    }

    public void removeDisplay(Player player) {
        UUID id = displays.remove(player.getUniqueId());

        if (id == null) {
            return;
        }

        Entity entity = Bukkit.getEntity(id);

        if (entity != null) {
            entity.remove();
        }
    }

    private String rankPrefix(Player player) {
        if (!config.getBoolean("rank.use-placeholderapi", true)) {
            return config.getString("rank.fallback", "");
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return config.getString("rank.fallback", "");
        }

        String placeholder = config.getString("rank.placeholder", "%luckperms_prefix%");

        try {
            String parsed = PlaceholderAPI.setPlaceholders(player, placeholder);

            if (parsed == null || parsed.isBlank() || parsed.equalsIgnoreCase(placeholder)) {
                return config.getString("rank.fallback", "");
            }

            return parsed;
        } catch (Throwable ignored) {
            return config.getString("rank.fallback", "");
        }
    }

    private String teamName(Player player) {
        int priority = player.isOp() ? 0 : 50;
        String clean = ChatColor.stripColor(player.getName()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");

        if (clean.length() > 10) {
            clean = clean.substring(0, 10);
        }

        return String.format("mn%02d_%s", priority, clean);
    }

    private void removeFromOtherMineacleTeams(Player player, Scoreboard scoreboard, String currentTeamName) {
        for (Team team : scoreboard.getTeams()) {
            if (team.getName().equals(currentTeamName)) {
                continue;
            }

            if (!team.getName().startsWith("mn")) {
                continue;
            }

            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
    }

    private void removeFromMineacleTeams(Player player, Scoreboard scoreboard) {
        for (Team team : scoreboard.getTeams()) {
            if (!team.getName().startsWith("mn")) {
                continue;
            }

            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    public void clear() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Player player : Bukkit.getOnlinePlayers()) {
            removeDisplay(player);
        }

        for (Team team : scoreboard.getTeams()) {
            if (!team.getName().startsWith("mn")) {
                continue;
            }

            team.unregister();
        }

        displays.clear();
    }
}
