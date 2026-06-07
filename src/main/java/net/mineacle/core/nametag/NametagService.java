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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.List;
import java.util.Locale;

public final class NametagService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

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
        return Math.max(1L, config.getLong("update-interval-seconds", 5L));
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
                clearPlayerCustomName(player);
                removeFromMineacleTeams(player, scoreboard);
                continue;
            }

            refresh(player, scoreboard);
        }
    }

    public void refresh(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        if (!enabled() || !enabledInWorld(player)) {
            clearPlayerCustomName(player);
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
            clearPlayerCustomName(player);
            team.prefix(Component.empty());
            team.suffix(Component.empty());
            team.setColor(ChatColor.WHITE);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            return;
        }

        team.prefix(Component.empty());
        team.suffix(Component.empty());
        team.setColor(player.isOp() ? ChatColor.LIGHT_PURPLE : ChatColor.WHITE);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

        player.customName(displayNameComponent(player));
        player.setCustomNameVisible(true);
    }

    private Component displayNameComponent(Player player) {
        return legacy(DisplayNames.prefixedDisplayName(player));
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

    private void clearPlayerCustomName(Player player) {
        player.customName(null);
        player.setCustomNameVisible(false);
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    public void clear() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Player player : Bukkit.getOnlinePlayers()) {
            clearPlayerCustomName(player);
        }

        for (Team team : scoreboard.getTeams()) {
            if (!team.getName().startsWith("mn")) {
                continue;
            }

            team.unregister();
        }
    }
}
