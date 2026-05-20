package net.mineacle.core.nametag;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.chat.service.NicknameService;
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

    public void refreshAll() {
        if (!enabled()) {
            return;
        }

        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player, scoreboard);
        }
    }

    public void refresh(Player player) {
        if (!enabled()) {
            return;
        }

        refresh(player, Bukkit.getScoreboardManager().getMainScoreboard());
    }

    private void refresh(Player player, Scoreboard scoreboard) {
        String teamName = teamName(player);
        Team team = scoreboard.getTeam(teamName);

        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }

        cleanupOtherTeams(player, scoreboard, teamName);

        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        team.prefix(prefix(player));
        team.suffix(Component.empty());
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
    }

    private Component prefix(Player player) {
        StringBuilder builder = new StringBuilder();

        HideService hideService = HideModule.service();

        if (hideService != null && hideService.isHidden(player.getUniqueId())) {
            builder.append(config.getString("hidden.admin-prefix", "&#ff88ff[Hidden] "));
        }

        String rank = rankPrefix(player);

        if (!rank.isBlank()) {
            builder.append(rank);
        }

        builder.append(nameColor(player));

        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(builder.toString()));
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

    private String nameColor(Player player) {
        if (player.isOp()) {
            return config.getString("colors.op", "#ff88ff");
        }

        return config.getString("colors.default", "#bbbbbb");
    }

    private String teamName(Player player) {
        int priority = player.isOp() ? 0 : 50;
        String clean = ChatColor.stripColor(player.getName()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");

        if (clean.length() > 10) {
            clean = clean.substring(0, 10);
        }

        return String.format("mn%02d_%s", priority, clean);
    }

    private void cleanupOtherTeams(Player player, Scoreboard scoreboard, String currentTeamName) {
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

    public void clear() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Team team : scoreboard.getTeams()) {
            if (!team.getName().startsWith("mn")) {
                continue;
            }

            team.unregister();
        }
    }
}
