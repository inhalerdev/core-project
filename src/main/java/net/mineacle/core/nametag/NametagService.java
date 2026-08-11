package net.mineacle.core.nametag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.collision.PlayerCollisionService;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.RankDisplayResolver;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.hide.HideModule;
import net.mineacle.core.hide.HideService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class NametagService {

    private final Core core;
    private final PlayerCollisionService collisionService;
    private final File file;
    private final NamespacedKey displayOwnerKey;

    private final Map<UUID, DisplayState> displays =
            new HashMap<>();
    private final Map<UUID, UUID> displayOwners =
            new HashMap<>();
    private final Set<UUID> mountWarnings =
            new HashSet<>();

    private boolean enabled;
    private long auditTicks;
    private double verticalOffset;
    private float viewRange;
    private int lineWidth;
    private boolean shadowed;
    private boolean seeThrough;
    private boolean defaultBackground;
    private String rankFallback;
    private String defaultNameColor;
    private String opNameColor;
    private String suffix;
    private boolean rankEnabled;
    private boolean worldRestrictionEnabled;
    private Set<String> enabledWorlds = Set.of();

    public NametagService(
            Core core,
            PlayerCollisionService collisionService
    ) {
        this.core = core;
        this.collisionService = collisionService;
        this.file = new File(
                core.getDataFolder(),
                "nametags.yml"
        );
        this.displayOwnerKey =
                new NamespacedKey(
                        core,
                        "nametag_owner"
                );

        removeOrphanDisplaysAtStartup();
        reload();
    }

    public void reload() {
        ensureDataFile();

        FileConfiguration config =
                YamlConfiguration
                        .loadConfiguration(file);

        enabled = config.getBoolean(
                "enabled",
                true
        );

        auditTicks = Math.clamp(
                config.getLong(
                        "updates.audit-ticks",
                        600L
                ),
                100L,
                20L * 60L * 30L
        );

        verticalOffset = clampFinite(
                config.getDouble(
                        "display.vertical-offset",
                        0.32D
                ),
                -1.0D,
                3.0D
        );

        viewRange =
                (float) clampFinite(
                        config.getDouble(
                                "display.view-range",
                                1.0D
                        ),
                        0.1D,
                        10.0D
                );

        lineWidth =
                (int) Math.clamp(
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
        defaultBackground =
                config.getBoolean(
                        "display.default-background",
                        false
                );

        rankEnabled = config.getBoolean(
                "rank.enabled",
                true
        );
        rankFallback =
                valueOrDefault(
                        config.getString(
                                "rank.fallback"
                        ),
                        ""
                );

        defaultNameColor =
                normalizeColor(
                        config.getString(
                                "name-color.default"
                        ),
                        "#bbbbbb"
                );
        opNameColor =
                normalizeOpNameColor(
                        config.getString(
                                "name-color.op"
                        )
                );
        suffix =
                valueOrDefault(
                        config.getString("suffix"),
                        ""
                );

        worldRestrictionEnabled =
                config.getBoolean(
                        "worlds.enabled",
                        false
                );

        Set<String> worlds =
                new HashSet<>();

        for (String world
                : config.getStringList(
                "worlds.list"
        )) {
            String canonical =
                    canonicalWorld(world);

            if (!canonical.isBlank()) {
                worlds.add(
                        canonical.toLowerCase(
                                Locale.ROOT
                        )
                );
            }
        }

        enabledWorlds =
                Set.copyOf(worlds);

        for (Player player
                : core.getServer()
                .getOnlinePlayers()) {
            rebuild(player);
        }
    }

    public long auditTicks() {
        return auditTicks;
    }

    public void audit() {
        Set<UUID> online =
                new HashSet<>();

        for (Player player
                : core.getServer()
                .getOnlinePlayers()) {
            online.add(
                    player.getUniqueId()
            );
            refresh(player);
        }

        for (UUID playerId
                : new ArrayList<>(
                displays.keySet()
        )) {
            if (!online.contains(playerId)) {
                removeDisplayOnly(playerId);
            }
        }

        collisionService.cleanupTeams();
    }

    public void refreshAll() {
        for (Player player
                : core.getServer()
                .getOnlinePlayers()) {
            refresh(player);
        }
    }

    public void refresh(Player player) {
        if (player == null
                || !player.isOnline()) {
            return;
        }

        if (!enabled
                || !enabledInWorld(player)) {
            removeDisplayOnly(
                    player.getUniqueId()
            );
            collisionService
                    .setNativeTagHidden(
                            player,
                            false
                    );
            return;
        }

        collisionService
                .setNativeTagHidden(
                        player,
                        true
                );

        if (shouldHideCustomTag(player)) {
            removeDisplayOnly(
                    player.getUniqueId()
            );
            return;
        }

        DisplayState state =
                ensureDisplay(player);

        if (state == null) {
            collisionService
                    .setNativeTagHidden(
                            player,
                            false
                    );
            return;
        }

        Component rendered =
                render(player);

        if (!rendered.equals(
                state.renderedText
        )) {
            state.display.text(rendered);
            state.renderedText = rendered;
        }

        player.hideEntity(
                core,
                state.display
        );
    }

    public void rebuild(Player player) {
        if (player == null) {
            return;
        }

        removeDisplayOnly(
                player.getUniqueId()
        );
        refresh(player);
    }

    /**
     * Kept for pose/listener compatibility. The tag's translation is now
     * relative to the passenger mount point instead of absolute world Y.
     * <p>
     * Paper places a passenger display on top of its vehicle. The old code
     * subtracted display.getLocation().getY() from the player's bounding-box
     * top. Immediately after mounting, that location could still be the
     * display's pre-mount spawn Y, producing a large upward translation until
     * a later pose change recalculated it. A fixed local translation removes
     * that stale-coordinate race completely.
     */
    public void refreshGeometry(Player player) {
        if (player == null
                || !player.isOnline()) {
            return;
        }

        DisplayState state =
                displays.get(
                        player.getUniqueId()
                );

        if (state == null
                || !state.validFor(player)) {
            return;
        }

        updateTransformation(
                state.display
        );
    }

    public boolean shouldTrack(
            Player viewer,
            Entity entity
    ) {
        UUID ownerId =
                displayOwners.get(
                        entity.getUniqueId()
                );

        if (ownerId == null) {
            return true;
        }

        if (viewer.getUniqueId()
                .equals(ownerId)) {
            return false;
        }

        Player owner =
                Bukkit.getPlayer(ownerId);

        return owner != null
                && owner.isOnline()
                && viewer.canSee(owner);
    }

    public void hideFrom(
            Player viewer,
            Player owner
    ) {
        if (viewer == null || owner == null) {
            return;
        }

        DisplayState state =
                displays.get(
                        owner.getUniqueId()
                );

        if (state != null
                && state.display.isValid()) {
            viewer.hideEntity(
                    core,
                    state.display
            );
        }
    }

    public void showTo(
            Player viewer,
            Player owner
    ) {
        if (viewer == null
                || owner == null
                || viewer.getUniqueId()
                .equals(
                        owner.getUniqueId()
                )
                || !viewer.canSee(owner)) {
            return;
        }

        DisplayState state =
                displays.get(
                        owner.getUniqueId()
                );

        if (state != null
                && state.display.isValid()) {
            viewer.showEntity(
                    core,
                    state.display
            );
        }
    }

    public void removePlayer(Player player) {
        if (player == null) {
            return;
        }

        removeDisplayOnly(
                player.getUniqueId()
        );
        mountWarnings.remove(
                player.getUniqueId()
        );
    }

    public void clear() {
        for (UUID playerId
                : new ArrayList<>(
                displays.keySet()
        )) {
            removeDisplayOnly(playerId);
        }

        for (Player player
                : core.getServer()
                .getOnlinePlayers()) {
            collisionService
                    .setNativeTagHidden(
                            player,
                            false
                    );
        }

        displays.clear();
        displayOwners.clear();
        mountWarnings.clear();
    }

    private boolean enabledInWorld(
            Player player
    ) {
        if (!worldRestrictionEnabled
                || enabledWorlds.isEmpty()) {
            return true;
        }

        return enabledWorlds.contains(
                canonicalWorld(
                        player.getWorld()
                                .getName()
                ).toLowerCase(
                        Locale.ROOT
                )
        );
    }

    private boolean shouldHideCustomTag(
            Player player
    ) {
        HideService hideService =
                HideModule.service();

        return hideService != null
                && hideService
                .shouldHideRealNametag(
                        player
                );
    }

    @Nullable
    private DisplayState ensureDisplay(
            Player player
    ) {
        DisplayState current =
                displays.get(
                        player.getUniqueId()
                );

        if (current != null
                && current.validFor(player)) {
            return current;
        }

        removeDisplayOnly(
                player.getUniqueId()
        );

        TextDisplay display =
                player.getWorld().spawn(
                        player.getLocation(),
                        TextDisplay.class,
                        spawned ->
                                configureDisplay(
                                        spawned,
                                        player
                                )
                );

        if (!player.addPassenger(display)) {
            display.remove();

            if (mountWarnings.add(
                    player.getUniqueId()
            )) {
                core.getLogger().warning(
                        "Could not attach nametag display to "
                                + player
                                .getUniqueId()
                );
            }

            return null;
        }

        mountWarnings.remove(
                player.getUniqueId()
        );

        /*
         * Translation is local to the passenger mount point, so it is safe to
         * apply immediately and remains correct when the player's pose changes.
         */
        updateTransformation(display);

        DisplayState created =
                new DisplayState(display);

        displays.put(
                player.getUniqueId(),
                created
        );
        displayOwners.put(
                display.getUniqueId(),
                player.getUniqueId()
        );

        player.hideEntity(
                core,
                display
        );

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
        display.setVisibleByDefault(true);
        display.setBillboard(
                Display.Billboard.CENTER
        );
        display.setAlignment(
                TextDisplay.TextAlignment.CENTER
        );
        display.setLineWidth(lineWidth);
        display.setShadowed(shadowed);
        display.setSeeThrough(seeThrough);
        display.setDefaultBackground(
                defaultBackground
        );
        display.setViewRange(viewRange);
        display.setTeleportDuration(0);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);

        display.getPersistentDataContainer()
                .set(
                        displayOwnerKey,
                        PersistentDataType.STRING,
                        owner.getUniqueId()
                                .toString()
                );

        updateTransformation(display);
    }

    private void updateTransformation(
            TextDisplay display
    ) {
        display.setTransformation(
                new Transformation(
                        new Vector3f(
                                0.0F,
                                (float) verticalOffset,
                                0.0F
                        ),
                        new Quaternionf(),
                        new Vector3f(
                                1.0F,
                                1.0F,
                                1.0F
                        ),
                        new Quaternionf()
                )
        );
    }

    private Component render(Player player) {
        StringBuilder value =
                new StringBuilder();

        String rank =
                stripTrailingSpaces(
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
        value.append(
                DisplayNames.displayName(
                        player
                )
        );

        if (!suffix.isBlank()) {
            value.append(suffix);
        }

        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(
                                value.toString()
                        )
                )
                .decoration(
                        TextDecoration.ITALIC,
                        false
                );
    }

    /**
     * Prefix is read directly from LuckPerms' cached metadata. No Mineacle
     * rank placeholder, group switch, or prefix table sits in between.
     */
    private String rankPrefix(Player player) {
        if (!rankEnabled) {
            return "";
        }

        String resolved =
                RankDisplayResolver.prefix(
                        player
                );

        return resolved == null
                || resolved.isBlank()
                ? rankFallback
                : resolved;
    }

    private void removeDisplayOnly(
            UUID playerId
    ) {
        DisplayState state =
                displays.remove(playerId);

        if (state == null) {
            return;
        }

        displayOwners.remove(
                state.display.getUniqueId()
        );

        if (state.display.getVehicle()
                != null) {
            state.display.leaveVehicle();
        }

        if (state.display.isValid()) {
            state.display.remove();
        }
    }

    private void removeOrphanDisplaysAtStartup() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity
                    : world.getEntities()) {
                if (!(entity
                        instanceof TextDisplay display)) {
                    continue;
                }

                String owner =
                        display
                                .getPersistentDataContainer()
                                .get(
                                        displayOwnerKey,
                                        PersistentDataType.STRING
                                );

                if (owner != null) {
                    display.remove();
                }
            }
        }
    }

    private void ensureDataFile() {
        File dataFolder =
                core.getDataFolder();

        if (!dataFolder.exists()
                && !dataFolder.mkdirs()
                && !dataFolder.exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()) {
            core.saveResource(
                    "nametags.yml",
                    false
            );
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize nametags.yml"
            );
        }
    }

    private String canonicalWorld(
            String rawWorld
    ) {
        if (rawWorld == null) {
            return "";
        }

        String trimmed = rawWorld.trim();

        return switch (
                trimmed.toLowerCase(
                        Locale.ROOT
                )
        ) {
            case "origins" -> "overworld";
            case "origins_nether" ->
                    "overworld_nether";
            case "origins_the_end" ->
                    "overworld_the_end";
            default -> trimmed;
        };
    }

    private String normalizeOpNameColor(
            String input
    ) {
        String normalized =
                normalizeColor(
                        input,
                        "#B078FF"
                );

        if (normalized.equalsIgnoreCase(
                "&#8436FE"
        ) || normalized.equalsIgnoreCase(
                "&#ff55ff"
        ) || normalized.equalsIgnoreCase(
                "&d"
        )) {
            return "&#B078FF";
        }

        return normalized;
    }

    private String normalizeColor(
            String input,
            String fallback
    ) {
        String cleaned =
                valueOrDefault(
                        input,
                        fallback
                ).trim();

        if (cleaned.matches(
                "(?i)^#[a-f0-9]{6}$"
        )) {
            return "&" + cleaned;
        }

        return cleaned;
    }

    private String valueOrDefault(
            String value,
            String fallback
    ) {
        return value == null
                || value.isBlank()
                ? fallback
                : value;
    }

    private String stripTrailingSpaces(
            String input
    ) {
        if (input == null
                || input.isEmpty()) {
            return "";
        }

        return input.replaceFirst(
                "\\s+$",
                ""
        );
    }

    private double clampFinite(
            double value,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)) {
            return minimum;
        }

        return Math.clamp(
                value,
                minimum,
                maximum
        );
    }

    private static final class DisplayState {

        private final TextDisplay display;
        private Component renderedText =
                Component.empty();

        private DisplayState(
                TextDisplay display
        ) {
            this.display = display;
        }

        private boolean validFor(
                Player player
        ) {
            return display.isValid()
                    && display.getWorld()
                    == player.getWorld()
                    && display.getVehicle()
                    == player;
        }
    }
}
