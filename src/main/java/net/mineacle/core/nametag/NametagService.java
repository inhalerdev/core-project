package net.mineacle.core.nametag;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.hide.HideModule;
import net.mineacle.core.hide.HideService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NametagService {

    private static final String TEAM_PREFIX = "mn_";

    private final Core core;
    private final File file;

    private FileConfiguration config;

    public NametagService(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "nametags.yml"
        );
        reload();
    }

    public void reload() {
        ensureDataFile();
        config = YamlConfiguration.loadConfiguration(file);
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public long updateIntervalTicks() {
        if (config.contains("update-interval-ticks")) {
            return Math.max(
                    1L,
                    config.getLong(
                            "update-interval-ticks",
                            20L
                    )
            );
        }

        return Math.max(
                2L,
                config.getLong(
                        "update-interval-seconds",
                        1L
                ) * 20L
        );
    }

    public boolean enabledInWorld(Player player) {
        if (player == null) {
            return false;
        }

        if (!config.getBoolean("worlds.enabled", true)) {
            return true;
        }

        List<String> worlds =
                config.getStringList("worlds.list");

        if (worlds.isEmpty()) {
            return true;
        }

        return worlds.stream().anyMatch(
                world -> world.equalsIgnoreCase(
                        player.getWorld().getName()
                )
        );
    }

    public void refreshAll() {
        Scoreboard scoreboard = mainScoreboard();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!enabled() || !enabledInWorld(player)) {
                removeFromMineacleTeams(player, scoreboard);
                continue;
            }

            refresh(player, scoreboard);
        }
    }

    public void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        Scoreboard scoreboard = mainScoreboard();

        if (!enabled() || !enabledInWorld(player)) {
            removeFromMineacleTeams(player, scoreboard);
            return;
        }

        refresh(player, scoreboard);
    }

    private void refresh(
            Player player,
            Scoreboard scoreboard
    ) {
        String currentTeamName = teamName(player);
        Team team = scoreboard.getTeam(currentTeamName);

        if (team == null) {
            team = scoreboard.registerNewTeam(
                    currentTeamName
            );
        }

        removeFromOtherMineacleTeams(
                player,
                scoreboard,
                currentTeamName
        );

        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        HideService hideService = HideModule.service();
        boolean hideNametag = hideService != null
                && hideService.shouldHideRealNametag(player);

        if (hideNametag) {
            team.prefix(Component.empty());
            team.suffix(Component.empty());
            team.color(NamedTextColor.WHITE);
            team.setOption(
                    Team.Option.NAME_TAG_VISIBILITY,
                    Team.OptionStatus.NEVER
            );
            return;
        }

        team.setOption(
                Team.Option.NAME_TAG_VISIBILITY,
                Team.OptionStatus.ALWAYS
        );
        team.color(teamTextColor(player));
        team.prefix(legacy(buildPrefix(player)));
        team.suffix(legacy(buildSuffix()));
    }

    private String buildPrefix(Player player) {
        StringBuilder prefix = new StringBuilder();

        if (config.getBoolean("rank.enabled", true)) {
            String rank = stripTrailingSpaces(
                    rankPrefix(player)
            );

            if (rank != null && !rank.isBlank()) {
                prefix.append(rank);

                if (config.getBoolean(
                        "rank.space-after-prefix",
                        false
                )) {
                    prefix.append(' ');
                }
            }
        }

        prefix.append(nameColor(player));

        return limitTeamPart(prefix.toString(), true);
    }

    private String buildSuffix() {
        return limitTeamPart(
                config.getString("suffix", ""),
                false
        );
    }

    private String nameColor(OfflinePlayer player) {
        if (!config.getBoolean(
                "name-color.enabled",
                true
        )) {
            return "";
        }

        if (player != null && player.isOp()) {
            return normalizeColor(
                    config.getString(
                            "name-color.op",
                            "&d"
                    )
            );
        }

        return normalizeColor(
                config.getString(
                        "name-color.default",
                        "&f"
                )
        );
    }

    private NamedTextColor teamTextColor(
            OfflinePlayer player
    ) {
        if (player != null && player.isOp()) {
            return NamedTextColor.LIGHT_PURPLE;
        }

        return NamedTextColor.WHITE;
    }

    private String rankPrefix(Player player) {
        if (!config.getBoolean(
                "rank.use-placeholderapi",
                true
        )) {
            return config.getString("rank.fallback", "");
        }

        if (Bukkit.getPluginManager()
                .getPlugin("PlaceholderAPI") == null) {
            return config.getString("rank.fallback", "");
        }

        String placeholder = config.getString(
                "rank.placeholder",
                "%luckperms_prefix%"
        );

        try {
            String parsed = PlaceholderAPI.setPlaceholders(
                    player,
                    placeholder
            );

            if (parsed == null
                    || parsed.isBlank()
                    || parsed.equalsIgnoreCase(placeholder)) {
                return config.getString(
                        "rank.fallback",
                        ""
                );
            }

            return parsed;
        } catch (Throwable ignored) {
            return config.getString("rank.fallback", "");
        }
    }

    /**
     * Scoreboard team names are limited, so use a stable UUID-derived key.
     * Username truncation can collide and make one player's hidden state
     * affect another player.
     */
    private String teamName(Player player) {
        String compact = player.getUniqueId()
                .toString()
                .replace("-", "");

        return TEAM_PREFIX
                + compact.substring(0, 7)
                + compact.substring(
                        compact.length() - 6
                );
    }

    private void removeFromOtherMineacleTeams(
            Player player,
            Scoreboard scoreboard,
            String currentTeamName
    ) {
        for (Team team : scoreboard.getTeams()) {
            if (team.getName().equals(currentTeamName)
                    || !team.getName().startsWith(
                    TEAM_PREFIX
            )) {
                continue;
            }

            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
    }

    private void removeFromMineacleTeams(
            Player player,
            Scoreboard scoreboard
    ) {
        for (Team team : scoreboard.getTeams()) {
            if (!team.getName().startsWith(TEAM_PREFIX)) {
                continue;
            }

            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
    }

    public void removeDisplay(Player player) {
        if (player != null) {
            removeFromMineacleTeams(
                    player,
                    mainScoreboard()
            );
        }
    }

    public void removeOrphanDisplays() {
        Scoreboard scoreboard = mainScoreboard();
        Set<String> onlineNames = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineNames.add(player.getName());
        }

        for (Team team
                : Set.copyOf(scoreboard.getTeams())) {
            if (!team.getName().startsWith(TEAM_PREFIX)) {
                continue;
            }

            for (String entry
                    : Set.copyOf(team.getEntries())) {
                if (!onlineNames.contains(entry)) {
                    team.removeEntry(entry);
                }
            }

            if (team.getEntries().isEmpty()) {
                team.unregister();
            }
        }
    }

    public void clear() {
        Scoreboard scoreboard = mainScoreboard();

        for (Team team
                : Set.copyOf(scoreboard.getTeams())) {
            if (team.getName().startsWith(TEAM_PREFIX)) {
                team.unregister();
            }
        }
    }

    private Scoreboard mainScoreboard() {
        return Bukkit.getScoreboardManager()
                .getMainScoreboard();
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(
                        TextColor.color(
                                message == null ? "" : message
                        )
                );
    }

    private String normalizeColor(String input) {
        if (input == null || input.isBlank()) {
            return "&f";
        }

        String cleaned = input.trim();

        if (cleaned.matches("(?i)^#[a-f0-9]{6}$")) {
            return "&" + cleaned;
        }

        return cleaned;
    }

    private String stripTrailingSpaces(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        return input.replaceFirst("\\s+$", "");
    }

    private String limitTeamPart(
            String input,
            boolean prefix
    ) {
        if (input == null) {
            return "";
        }

        int maximum = Math.max(
                16,
                config.getInt(
                        prefix
                                ? "limits.prefix"
                                : "limits.suffix",
                        64
                )
        );

        if (input.length() <= maximum) {
            return input;
        }

        return input.substring(0, maximum);
    }

    private void ensureDataFile() {
        File dataFolder = core.getDataFolder();

        if (!dataFolder.exists()
                && !dataFolder.mkdirs()
                && !dataFolder.exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()) {
            core.saveResource("nametags.yml", false);
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize nametags.yml"
            );
        }
    }
}
