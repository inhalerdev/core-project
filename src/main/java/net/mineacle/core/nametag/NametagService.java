package net.mineacle.core.nametag;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.hide.HideModule;
import net.mineacle.core.hide.HideService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class NametagService {

    private static final String TEAM_PREFIX = "mn_";
    private static final long MOVEMENT_UPDATE_TICKS = 1L;

    private final Core core;
    private final File file;
    private final NamespacedKey displayOwnerKey;

    private final Map<UUID, DisplayState> displays =
            new HashMap<>();
    private final Set<UUID> warnedForeignTeams =
            new HashSet<>();

    private FileConfiguration config;

    private boolean enabled;
    private long contentUpdateTicks;
    private long cleanupIntervalTicks;
    private double verticalOffset;
    private float viewRange;
    private int lineWidth;
    private boolean shadowed;
    private boolean seeThrough;
    private boolean defaultBackground;
    private boolean disableCollision;
    private String rankPlaceholder;
    private String rankFallback;
    private String rankSeparator;
    private String defaultNameColor;
    private String opNameColor;
    private String suffix;
    private boolean rankEnabled;
    private boolean usePlaceholderApi;
    private boolean worldRestrictionEnabled;
    private Set<String> enabledWorlds = Set.of();

    public NametagService(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "nametags.yml"
        );
        this.displayOwnerKey = new NamespacedKey(
                core,
                "nametag_owner"
        );

        reload();
        removeOrphanDisplays();
    }

    public void reload() {
        ensureDataFile();
        config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        contentUpdateTicks = clampLong(
                config.getLong(
                        "updates.content-ticks",
                        20L
                ),
                5L,
                20L * 60L
        );
        cleanupIntervalTicks = clampLong(
                config.getLong(
                        "updates.cleanup-ticks",
                        600L
                ),
                100L,
                20L * 60L * 30L
        );
        verticalOffset = clampDouble(
                config.getDouble(
                        "display.vertical-offset",
                        0.32D
                ),
                -1.0D,
                3.0D
        );
        viewRange = (float) clampDouble(
                config.getDouble(
                        "display.view-range",
                        1.0D
                ),
                0.1D,
                10.0D
        );
        lineWidth = (int) clampLong(
                config.getLong(
                        "display.line-width",
                        200L
                ),
                20L,
                1_000L
        );
        shadowed = config.getBoolean(
                "display.shadowed",
                true
        );
        seeThrough = config.getBoolean(
                "display.see-through",
                false
        );
        defaultBackground = config.getBoolean(
                "display.default-background",
                false
        );
        disableCollision = config.getBoolean(
                "collision.disabled",
                true
        );

        rankEnabled = config.getBoolean(
                "rank.enabled",
                true
        );
        usePlaceholderApi = config.getBoolean(
                "rank.use-placeholderapi",
                true
        );
        rankPlaceholder = config.getString(
                "rank.placeholder",
                "%luckperms_prefix%"
        );
        rankFallback = config.getString(
                "rank.fallback",
                ""
        );
        rankSeparator = config.getString(
                "rank.separator",
                " "
        );
        defaultNameColor = normalizeColor(
                config.getString(
                        "name-color.default",
                        "&f"
                ),
                "&f"
        );
        opNameColor = normalizeColor(
                config.getString(
                        "name-color.op",
                        "&d"
                ),
                "&d"
        );
        suffix = config.getString("suffix", "");

        worldRestrictionEnabled = config.getBoolean(
                "worlds.enabled",
                false
        );
        Set<String> worlds = new HashSet<>();

        for (String world : config.getStringList(
                "worlds.list"
        )) {
            String canonical = canonicalWorld(world);

            if (!canonical.isBlank()) {
                worlds.add(
                        canonical.toLowerCase(Locale.ROOT)
                );
            }
        }

        enabledWorlds = Set.copyOf(worlds);

        /*
         * Recreate live displays so visual settings such as line width,
         * background, view range, and vertical offset apply after reload.
         */
        for (UUID playerId : new ArrayList<>(
                displays.keySet()
        )) {
            removeDisplayOnly(playerId);
        }
    }

    public long contentUpdateTicks() {
        return contentUpdateTicks;
    }

    public long movementUpdateTicks() {
        return MOVEMENT_UPDATE_TICKS;
    }

    public long cleanupIntervalTicks() {
        return cleanupIntervalTicks;
    }

    public void refreshAll() {
        Set<UUID> online = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            refresh(player);
        }

        for (UUID playerId : new ArrayList<>(
                displays.keySet()
        )) {
            if (!online.contains(playerId)) {
                removeDisplay(playerId);
            }
        }
    }

    public void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!enabled || !enabledInWorld(player)) {
            removeDisplayOnly(player.getUniqueId());
            releaseNativeTeam(player);
            return;
        }

        ensureNativeTagHidden(player);

        if (shouldHideCustomTag(player)) {
            removeDisplayOnly(player.getUniqueId());
            return;
        }

        DisplayState state = ensureDisplay(player);
        Component rendered = render(player);

        if (!rendered.equals(state.renderedText)) {
            state.display.text(rendered);
            state.renderedText = rendered;
        }

        moveDisplay(player, state, true);
        syncVisibility(player, state);
    }

    public void refreshViewer(Player viewer) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }

        for (Map.Entry<UUID, DisplayState> entry
                : new ArrayList<>(displays.entrySet())) {
            Player owner = Bukkit.getPlayer(entry.getKey());

            if (owner == null || !owner.isOnline()) {
                continue;
            }

            syncVisibility(
                    owner,
                    entry.getValue(),
                    viewer
            );
        }
    }

    public void tickPositions() {
        for (Map.Entry<UUID, DisplayState> entry
                : new ArrayList<>(displays.entrySet())) {
            UUID playerId = entry.getKey();
            Player player = Bukkit.getPlayer(playerId);

            if (player == null || !player.isOnline()) {
                removeDisplay(playerId);
                continue;
            }

            if (!enabled
                    || !enabledInWorld(player)
                    || shouldHideCustomTag(player)) {
                removeDisplayOnly(playerId);
                continue;
            }

            DisplayState state = entry.getValue();

            if (!state.validFor(player)) {
                removeDisplayOnly(playerId);
                state = ensureDisplay(player);
                Component rendered = render(player);
                state.display.text(rendered);
                state.renderedText = rendered;
                syncVisibility(player, state);
            }

            moveDisplay(player, state, false);
        }
    }

    public void removeDisplay(Player player) {
        if (player == null) {
            return;
        }

        removeDisplay(player.getUniqueId());
        releaseNativeTeam(player);
        warnedForeignTeams.remove(player.getUniqueId());
    }

    public void removeOrphanDisplays() {
        Set<UUID> activeEntityIds = new HashSet<>();

        for (DisplayState state : displays.values()) {
            if (state.display != null
                    && state.display.isValid()) {
                activeEntityIds.add(
                        state.display.getUniqueId()
                );
            }
        }

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof TextDisplay display)) {
                    continue;
                }

                String owner = display
                        .getPersistentDataContainer()
                        .get(
                                displayOwnerKey,
                                PersistentDataType.STRING
                        );

                if (owner == null) {
                    continue;
                }

                if (!activeEntityIds.contains(
                        display.getUniqueId()
                )) {
                    display.remove();
                }
            }
        }

        cleanupScoreboardTeams();
    }

    public void clear() {
        for (UUID playerId : new ArrayList<>(
                displays.keySet()
        )) {
            removeDisplayOnly(playerId);
        }

        Scoreboard scoreboard = mainScoreboard();

        if (scoreboard != null) {
            for (Team team : Set.copyOf(
                    scoreboard.getTeams()
            )) {
                if (team.getName().startsWith(
                        TEAM_PREFIX
                )) {
                    team.unregister();
                }
            }
        }

        displays.clear();
        warnedForeignTeams.clear();
    }

    private boolean enabledInWorld(Player player) {
        if (!worldRestrictionEnabled) {
            return true;
        }

        if (enabledWorlds.isEmpty()) {
            return true;
        }

        return enabledWorlds.contains(
                canonicalWorld(
                        player.getWorld().getName()
                ).toLowerCase(Locale.ROOT)
        );
    }

    private boolean shouldHideCustomTag(Player player) {
        HideService hideService = HideModule.service();

        return hideService != null
                && hideService.shouldHideRealNametag(player);
    }

    private DisplayState ensureDisplay(Player player) {
        DisplayState current = displays.get(
                player.getUniqueId()
        );

        if (current != null && current.validFor(player)) {
            return current;
        }

        removeDisplayOnly(player.getUniqueId());

        Location location = displayLocation(player);
        TextDisplay display = player.getWorld().spawn(
                location,
                TextDisplay.class,
                spawned -> configureDisplay(
                        spawned,
                        player
                )
        );
        DisplayState created =
                new DisplayState(display);
        displays.put(player.getUniqueId(), created);

        return created;
    }

    private void configureDisplay(
            TextDisplay display,
            Player owner
    ) {
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setGravity(false);
        display.setVisibleByDefault(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setAlignment(
                TextDisplay.TextAlignment.CENTER
        );
        display.setLineWidth(lineWidth);
        display.setShadowed(shadowed);
        display.setSeeThrough(seeThrough);
        display.setDefaultBackground(defaultBackground);
        display.setViewRange(viewRange);
        /*
         * Position interpolation makes a separately tracked display chase
         * the player. Movement is already updated every server tick, so
         * applying each position immediately keeps both entities together.
         */
        display.setTeleportDuration(0);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
        display.getPersistentDataContainer().set(
                displayOwnerKey,
                PersistentDataType.STRING,
                owner.getUniqueId().toString()
        );
    }

    private Component render(Player player) {
        StringBuilder value = new StringBuilder();
        String rank = stripTrailingSpaces(
                rankPrefix(player)
        );

        if (!rank.isBlank()) {
            value.append(rank);
            value.append(' ');
        }

        value.append(
                player.isOp()
                        ? opNameColor
                        : defaultNameColor
        );
        value.append(DisplayNames.displayName(player));

        if (suffix != null && !suffix.isBlank()) {
            value.append(suffix);
        }

        return LegacyComponentSerializer.legacySection()
                .deserialize(
                        TextColor.color(value.toString())
                )
                .decoration(TextDecoration.ITALIC, false);
    }

    private String rankPrefix(Player player) {
        if (!rankEnabled) {
            return "";
        }

        if (!usePlaceholderApi
                || Bukkit.getPluginManager()
                .getPlugin("PlaceholderAPI") == null) {
            return rankFallback == null
                    ? ""
                    : rankFallback;
        }

        try {
            String parsed = PlaceholderAPI.setPlaceholders(
                    player,
                    rankPlaceholder
            );

            if (parsed == null
                    || parsed.isBlank()
                    || parsed.equalsIgnoreCase(
                    rankPlaceholder
            )
                    || parsed.contains(rankPlaceholder)) {
                return rankFallback == null
                        ? ""
                        : rankFallback;
            }

            return parsed;
        } catch (Throwable exception) {
            return rankFallback == null
                    ? ""
                    : rankFallback;
        }
    }

    private void moveDisplay(
            Player player,
            DisplayState state,
            boolean force
    ) {
        if (state == null
                || state.display == null
                || !state.display.isValid()) {
            return;
        }

        Location target = displayLocation(player);
        Location current = state.display.getLocation();

        if (force
                || current.getWorld() != target.getWorld()
                || current.distanceSquared(target) > 0.0004D) {
            state.display.teleport(target);
        }
    }

    private Location displayLocation(Player player) {
        BoundingBox bounds = player.getBoundingBox();
        double x = (bounds.getMinX() + bounds.getMaxX())
                / 2.0D;
        double z = (bounds.getMinZ() + bounds.getMaxZ())
                / 2.0D;
        double y = bounds.getMaxY() + verticalOffset;

        return new Location(
                player.getWorld(),
                x,
                y,
                z
        );
    }

    private void syncVisibility(
            Player owner,
            DisplayState state
    ) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            syncVisibility(owner, state, viewer);
        }

        state.visibleTo.removeIf(
                viewerId -> Bukkit.getPlayer(viewerId) == null
        );
    }

    private void syncVisibility(
            Player owner,
            DisplayState state,
            Player viewer
    ) {
        if (state == null
                || state.display == null
                || !state.display.isValid()
                || viewer == null
                || !viewer.isOnline()) {
            return;
        }

        boolean shouldSee =
                !viewer.getUniqueId().equals(
                        owner.getUniqueId()
                )
                        && viewer.getWorld() == owner.getWorld()
                        && viewer.canSee(owner)
                        && enabledInWorld(owner)
                        && !shouldHideCustomTag(owner);
        boolean currentlyVisible =
                state.visibleTo.contains(
                        viewer.getUniqueId()
                );

        if (shouldSee == currentlyVisible) {
            return;
        }

        if (shouldSee) {
            viewer.showEntity(core, state.display);
            state.visibleTo.add(viewer.getUniqueId());
        } else {
            viewer.hideEntity(core, state.display);
            state.visibleTo.remove(viewer.getUniqueId());
        }
    }

    private void ensureNativeTagHidden(Player player) {
        Scoreboard scoreboard = mainScoreboard();

        if (scoreboard == null) {
            return;
        }

        String entry = player.getName();
        String expectedTeamName = teamName(player);
        Team currentTeam = scoreboard.getEntryTeam(entry);

        if (currentTeam != null
                && !currentTeam.getName()
                .startsWith(TEAM_PREFIX)
                && warnedForeignTeams.add(
                player.getUniqueId()
        )) {
            core.getLogger().warning(
                    "Nametags moved "
                            + entry
                            + " out of scoreboard team "
                            + currentTeam.getName()
                            + " — disable external nametag "
                            + "team management in TAB or other plugins"
            );
        }

        Team team = scoreboard.getTeam(expectedTeamName);

        if (team == null) {
            team = scoreboard.registerNewTeam(
                    expectedTeamName
            );
        }

        removeFromOtherMineacleTeams(
                player,
                scoreboard,
                expectedTeamName
        );

        if (!team.hasEntry(entry)) {
            team.addEntry(entry);
        }

        team.prefix(Component.empty());
        team.suffix(Component.empty());
        team.setOption(
                Team.Option.NAME_TAG_VISIBILITY,
                Team.OptionStatus.NEVER
        );
        team.setOption(
                Team.Option.COLLISION_RULE,
                disableCollision
                        ? Team.OptionStatus.NEVER
                        : Team.OptionStatus.ALWAYS
        );
        team.setAllowFriendlyFire(true);
        team.setCanSeeFriendlyInvisibles(false);
    }

    private void releaseNativeTeam(Player player) {
        Scoreboard scoreboard = mainScoreboard();

        if (scoreboard == null || player == null) {
            return;
        }

        String entry = player.getName();

        for (Team team : Set.copyOf(
                scoreboard.getTeams()
        )) {
            if (!team.getName().startsWith(TEAM_PREFIX)
                    || !team.hasEntry(entry)) {
                continue;
            }

            team.removeEntry(entry);

            if (team.getEntries().isEmpty()) {
                team.unregister();
            }
        }
    }

    private void removeFromOtherMineacleTeams(
            Player player,
            Scoreboard scoreboard,
            String expectedTeamName
    ) {
        for (Team team : Set.copyOf(
                scoreboard.getTeams()
        )) {
            if (team.getName().equals(expectedTeamName)
                    || !team.getName().startsWith(
                    TEAM_PREFIX
            )) {
                continue;
            }

            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());

                if (team.getEntries().isEmpty()) {
                    team.unregister();
                }
            }
        }
    }

    private void cleanupScoreboardTeams() {
        Scoreboard scoreboard = mainScoreboard();

        if (scoreboard == null) {
            return;
        }

        Set<String> onlineNames = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineNames.add(player.getName());
        }

        for (Team team : Set.copyOf(
                scoreboard.getTeams()
        )) {
            if (!team.getName().startsWith(TEAM_PREFIX)) {
                continue;
            }

            for (String entry : Set.copyOf(
                    team.getEntries()
            )) {
                if (!onlineNames.contains(entry)) {
                    team.removeEntry(entry);
                }
            }

            if (team.getEntries().isEmpty()) {
                team.unregister();
            }
        }
    }

    private void removeDisplay(UUID playerId) {
        removeDisplayOnly(playerId);

        Player player = Bukkit.getPlayer(playerId);

        if (player != null) {
            releaseNativeTeam(player);
        }
    }

    private void removeDisplayOnly(UUID playerId) {
        DisplayState state = displays.remove(playerId);

        if (state == null) {
            return;
        }

        if (state.display != null
                && state.display.isValid()) {
            state.display.remove();
        }

        state.visibleTo.clear();
    }

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

    private Scoreboard mainScoreboard() {
        ScoreboardManager manager =
                Bukkit.getScoreboardManager();

        return manager == null
                ? null
                : manager.getMainScoreboard();
    }

    private String canonicalWorld(String rawWorld) {
        if (rawWorld == null) {
            return "";
        }

        String trimmed = rawWorld.trim();

        return switch (
                trimmed.toLowerCase(Locale.ROOT)
        ) {
            case "origins" -> "overworld";
            case "origins_nether" ->
                    "overworld_nether";
            case "origins_the_end" ->
                    "overworld_the_end";
            default -> trimmed;
        };
    }

    private String normalizeColor(
            String input,
            String fallback
    ) {
        if (input == null || input.isBlank()) {
            return fallback;
        }

        String cleaned = input.trim();

        if (cleaned.matches(
                "(?i)^#[a-f0-9]{6}$"
        )) {
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

    private long clampLong(
            long value,
            long minimum,
            long maximum
    ) {
        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }

    private double clampDouble(
            double value,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)) {
            return minimum;
        }

        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
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

    private static final class DisplayState {

        private final TextDisplay display;
        private final Set<UUID> visibleTo =
                new HashSet<>();
        private Component renderedText = Component.empty();

        private DisplayState(TextDisplay display) {
            this.display = display;
        }

        private boolean validFor(Player player) {
            return display != null
                    && display.isValid()
                    && display.getWorld()
                    == player.getWorld();
        }
    }
}
