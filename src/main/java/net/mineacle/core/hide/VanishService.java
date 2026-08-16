package net.mineacle.core.hide;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.collision.CollisionModule;
import net.mineacle.core.collision.PlayerCollisionService;
import net.mineacle.core.common.player.VanishRegistry;
import net.mineacle.core.common.player.VanishRegistry.WebPrivacySnapshot;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.stats.StatsModule;
import net.mineacle.core.stats.service.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishService {

    private static final String DEFAULT_USE_PERMISSION =
            "mineaclevanish.use";
    private static final String DEFAULT_SEE_PERMISSION =
            "mineaclevanish.see";
    private static final String TAB_VANISH_METADATA =
            "vanished";

    private final Core core;
    private final File file;
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> onlineVanished =
            ConcurrentHashMap.newKeySet();
    private final Map<UUID, RuntimeState> runtime =
            new HashMap<>();

    private FileConfiguration config;
    private BukkitTask actionbarTask;

    public VanishService(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "vanish.yml"
        );
        reload();
    }

    public void reload() {
        ensureDataFile();
        config = YamlConfiguration.loadConfiguration(file);

        vanished.clear();
        onlineVanished.clear();
        VanishRegistry.clear();

        if (!enabled()) {
            return;
        }

        if (persistAcrossRestarts()) {
            for (String raw : config.getStringList("vanished")) {
                try {
                    UUID playerId = UUID.fromString(raw);
                    vanished.add(playerId);

                    WebPrivacySnapshot snapshot =
                            loadWebPrivacySnapshot(
                                    playerId
                            );

                    if (snapshot == null) {
                        snapshot =
                                fallbackWebPrivacySnapshot(
                                        playerId
                                );
                    }

                    VanishRegistry.setWebPrivacySnapshot(
                            playerId,
                            snapshot
                    );
                    VanishRegistry.setVanished(
                            playerId,
                            true
                    );
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed persisted UUIDs safely.
                }
            }
        }
    }

    public void start() {
        stopActionbar();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isVanished(player.getUniqueId())) {
                applyVanishedState(player);
            }
        }
        applyAllViewers();

        if (config.getBoolean(
                "indicator.enabled",
                false
        )) {
            long interval = Math.clamp(
                    config.getLong(
                            "indicator.interval-ticks",
                            100L
                    ),
                    40L,
                    1200L
            );

            actionbarTask = core.getServer()
                    .getScheduler()
                    .runTaskTimer(
                            core,
                            this::sendActionbars,
                            interval,
                            interval
                    );
        }
    }

    public void stop() {
        stopActionbar();

        List<Player> restoredPlayers = new ArrayList<>();

        for (UUID playerId : Set.copyOf(vanished)) {
            Player player = Bukkit.getPlayer(playerId);

            if (player != null && player.isOnline()) {
                restoreRuntimeState(player);
                showToAll(player);
                restoredPlayers.add(player);
            }
        }

        runtime.clear();
        onlineVanished.clear();
        VanishRegistry.clear();

        for (Player player : restoredPlayers) {
            refreshCollision(player);
        }
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean cannotUse(Player player) {
        return player == null
                || !player.hasPermission(usePermission());
    }

    public boolean canSee(Player viewer) {
        return viewer != null
                && viewer.hasPermission(seePermission());
    }

    public String usePermission() {
        return config.getString(
                "permission",
                DEFAULT_USE_PERMISSION
        );
    }

    public String seePermission() {
        return config.getString(
                "see-permission",
                DEFAULT_SEE_PERMISSION
        );
    }

    public boolean isVanished(UUID playerId) {
        return playerId != null && vanished.contains(playerId);
    }

    public Set<UUID> onlineVanishedSnapshot() {
        return Set.copyOf(onlineVanished);
    }

    public boolean toggle(Player player) {
        if (isVanished(player.getUniqueId())) {
            show(player);
            return false;
        }

        hide(player);
        return true;
    }

    public void hide(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID playerId = player.getUniqueId();

        VanishRegistry.setWebPrivacySnapshot(
                playerId,
                captureWebPrivacySnapshot(player)
        );
        vanished.add(playerId);
        VanishRegistry.setVanished(playerId, true);
        applyVanishedState(player);
        refreshCollision(player);
        applyToViewers(player);
        saveState();
        audit(player, "enabled");
    }

    public void show(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        vanished.remove(playerId);
        onlineVanished.remove(playerId);
        VanishRegistry.setVanished(playerId, false);

        if (player.isOnline()) {
            restoreRuntimeState(player);
            refreshCollision(player);
            showToAll(player);
        } else {
            runtime.remove(playerId);
        }

        saveState();
        audit(player, "disabled");
    }

    public void handleJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        applyViewer(player);

        if (!isVanished(player.getUniqueId())) {
            onlineVanished.remove(player.getUniqueId());
            clearTabVanishMetadata(player);
            return;
        }

        if (cannotUse(player)) {
            show(player);
            return;
        }

        applyVanishedState(player);
        refreshCollision(player);
        applyToViewers(player);
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }

        runtime.remove(player.getUniqueId());
        onlineVanished.remove(player.getUniqueId());

        if (!persistAcrossRestarts()
                && vanished.remove(player.getUniqueId())) {
            VanishRegistry.setVanished(
                    player.getUniqueId(),
                    false
            );
            clearTabVanishMetadata(player);
            saveState();
        }
    }

    public void reapply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!isVanished(player.getUniqueId())) {
            onlineVanished.remove(player.getUniqueId());
            clearTabVanishMetadata(player);
            return;
        }

        if (cannotUse(player)) {
            show(player);
            return;
        }

        applyVanishedState(player);
        refreshCollision(player);
        applyToViewers(player);
    }

    public void applyViewer(Player viewer) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }

        for (UUID playerId : Set.copyOf(vanished)) {
            Player hiddenPlayer = Bukkit.getPlayer(playerId);

            if (hiddenPlayer == null
                    || !hiddenPlayer.isOnline()
                    || hiddenPlayer.getUniqueId()
                    .equals(viewer.getUniqueId())) {
                continue;
            }

            if (canSee(viewer)) {
                viewer.showPlayer(core, hiddenPlayer);
            } else {
                viewer.hidePlayer(core, hiddenPlayer);
            }
        }
    }

    public boolean interactionLocked(Player player) {
        return player != null
                && isVanished(player.getUniqueId())
                && config.getBoolean(
                "stealth.prevent-world-interactions",
                true
        );
    }

    public String message(String path, String fallback) {
        return config.getString(
                "messages." + path,
                fallback
        );
    }

    private WebPrivacySnapshot captureWebPrivacySnapshot(
            Player player
    ) {
        if (player == null) {
            return new WebPrivacySnapshot(
                    0L,
                    "0m",
                    0L,
                    0L,
                    0.0D,
                    0L
            );
        }

        UUID playerId = player.getUniqueId();
        StatsService stats = StatsModule.statsService();

        long playtime = stats == null
                ? 0L
                : stats.playtimeSeconds(playerId);
        long kills = stats == null
                ? 0L
                : stats.kills(playerId);
        long deaths = stats == null
                ? 0L
                : stats.deaths(playerId);

        return new WebPrivacySnapshot(
                playtime,
                stats == null
                        ? formatPlaytime(playtime)
                        : stats.formatPlaytime(playtime),
                kills,
                deaths,
                kdRatio(kills, deaths),
                System.currentTimeMillis()
        );
    }

    private WebPrivacySnapshot fallbackWebPrivacySnapshot(
            UUID playerId
    ) {
        StatsService stats = StatsModule.statsService();

        long playtime = stats == null
                ? 0L
                : stats.playtimeSeconds(playerId);
        long kills = stats == null
                ? 0L
                : stats.kills(playerId);
        long deaths = stats == null
                ? 0L
                : stats.deaths(playerId);

        OfflinePlayer offline =
                Bukkit.getOfflinePlayer(playerId);
        long lastSeen = Math.max(
                0L,
                offline.getLastSeen()
        );

        return new WebPrivacySnapshot(
                playtime,
                stats == null
                        ? formatPlaytime(playtime)
                        : stats.formatPlaytime(playtime),
                kills,
                deaths,
                kdRatio(kills, deaths),
                lastSeen
        );
    }

    private WebPrivacySnapshot loadWebPrivacySnapshot(
            UUID playerId
    ) {
        String path =
                "web-privacy."
                        + playerId
                        + ".";

        if (!config.contains(
                path + "playtime-seconds"
        )) {
            return null;
        }

        return new WebPrivacySnapshot(
                config.getLong(
                        path + "playtime-seconds",
                        0L
                ),
                config.getString(
                        path + "playtime-formatted",
                        "0m"
                ),
                config.getLong(
                        path + "kills",
                        0L
                ),
                config.getLong(
                        path + "deaths",
                        0L
                ),
                config.getDouble(
                        path + "kd-ratio",
                        0.0D
                ),
                config.getLong(
                        path + "last-seen",
                        0L
                )
        );
    }

    private double kdRatio(
            long kills,
            long deaths
    ) {
        if (deaths <= 0L) {
            return Math.max(0L, kills);
        }

        return Math.round(
                (kills / (double) deaths)
                        * 100.0D
        ) / 100.0D;
    }

    private String formatPlaytime(long totalSeconds) {
        long safe = Math.max(
                0L,
                totalSeconds
        );
        long days = safe / 86400L;
        long hours =
                (safe % 86400L) / 3600L;
        long minutes =
                (safe % 3600L) / 60L;

        if (days > 0L) {
            return days + "d " + hours + "h";
        }

        if (hours > 0L) {
            return hours + "h "
                    + minutes + "m";
        }

        return minutes + "m";
    }

    private void refreshCollision(Player player) {
        PlayerCollisionService collision =
                CollisionModule.service();

        if (collision != null) {
            collision.apply(player);
        }
    }

    private void applyAllViewers() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyViewer(viewer);
        }
    }

    private void applyToViewers(Player hiddenPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId()
                    .equals(hiddenPlayer.getUniqueId())) {
                continue;
            }

            if (canSee(viewer)) {
                viewer.showPlayer(core, hiddenPlayer);
            } else {
                viewer.hidePlayer(core, hiddenPlayer);
            }
        }
    }

    private void showToAll(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId()
                    .equals(player.getUniqueId())) {
                viewer.showPlayer(core, player);
            }
        }
    }

    private void applyVanishedState(Player player) {
        onlineVanished.add(player.getUniqueId());
        setTabVanishMetadata(player);

        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }

        for (Entity entity : player.getNearbyEntities(
                64.0D,
                64.0D,
                64.0D
        )) {
            if (entity instanceof Mob mob
                    && mob.getTarget() == player) {
                mob.setTarget(null);
            }
        }

        runtime.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new RuntimeState(
                        player.isCollidable(),
                        player.getCanPickupItems(),
                        player.isSilent(),
                        player.isInvulnerable()
                )
        );

        if (config.getBoolean("stealth.disable-collision", true)) {
            player.setCollidable(false);
        }
        if (config.getBoolean("stealth.disable-item-pickup", true)) {
            player.setCanPickupItems(false);
        }
        if (config.getBoolean("stealth.silent-entity", true)) {
            player.setSilent(true);
        }
        if (config.getBoolean("stealth.invulnerable", true)) {
            player.setInvulnerable(true);
        }
    }

    private void restoreRuntimeState(Player player) {
        onlineVanished.remove(player.getUniqueId());
        clearTabVanishMetadata(player);

        RuntimeState state = runtime.remove(player.getUniqueId());

        if (state == null) {
            player.setCollidable(true);
            player.setCanPickupItems(true);
            player.setSilent(false);
            player.setInvulnerable(false);
            return;
        }

        player.setCollidable(state.collidable());
        player.setCanPickupItems(state.canPickupItems());
        player.setSilent(state.silent());
        player.setInvulnerable(state.invulnerable());
    }

    @SuppressWarnings("deprecation")
    private void setTabVanishMetadata(Player player) {
        player.setMetadata(
                TAB_VANISH_METADATA,
                new FixedMetadataValue(core, true)
        );
    }

    private void clearTabVanishMetadata(Player player) {
        player.removeMetadata(
                TAB_VANISH_METADATA,
                core
        );
    }

    private void sendActionbars() {
        if (vanished.isEmpty()) {
            return;
        }

        Component component = LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(
                                message(
                                        "actionbar",
                                        "&eVANISHED"
                                )
                        )
                );

        for (UUID playerId : Set.copyOf(vanished)) {
            Player player = Bukkit.getPlayer(playerId);

            if (player != null && player.isOnline()) {
                player.sendActionBar(component);
            }
        }
    }

    private boolean persistAcrossRestarts() {
        return config == null
                || config.getBoolean(
                "persist-across-restarts",
                true
        );
    }

    private void saveState() {
        List<String> values = new ArrayList<>();

        if (persistAcrossRestarts()) {
            for (UUID playerId : vanished) {
                values.add(playerId.toString());
            }
            values.sort(String.CASE_INSENSITIVE_ORDER);
        }

        config.set("vanished", values);
        config.set("web-privacy", null);

        if (persistAcrossRestarts()) {
            for (UUID playerId : vanished) {
                WebPrivacySnapshot snapshot =
                        VanishRegistry
                                .webPrivacySnapshot(
                                        playerId
                                );

                if (snapshot == null) {
                    snapshot =
                            fallbackWebPrivacySnapshot(
                                    playerId
                            );
                    VanishRegistry
                            .setWebPrivacySnapshot(
                                    playerId,
                                    snapshot
                            );
                }

                String path =
                        "web-privacy."
                                + playerId
                                + ".";

                config.set(
                        path + "playtime-seconds",
                        snapshot.playtimeSeconds()
                );
                config.set(
                        path + "playtime-formatted",
                        snapshot.playtimeFormatted()
                );
                config.set(
                        path + "kills",
                        snapshot.kills()
                );
                config.set(
                        path + "deaths",
                        snapshot.deaths()
                );
                config.set(
                        path + "kd-ratio",
                        snapshot.kdRatio()
                );
                config.set(
                        path + "last-seen",
                        snapshot.lastSeen()
                );
            }
        }

        File temporary = new File(
                file.getParentFile(),
                file.getName() + ".tmp"
        );

        try {
            config.save(temporary);

            try {
                Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            core.getLogger().warning(
                    "[Vanish] Could not save vanish.yml: "
                            + exception.getMessage()
            );
        } finally {
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
                // Best-effort temporary file cleanup.
            }
        }
    }

    private void audit(Player player, String action) {
        if (!config.getBoolean("audit.enabled", true)) {
            return;
        }

        core.getLogger().info(
                "[Vanish] "
                        + player.getName()
                        + " ("
                        + player.getUniqueId()
                        + ") "
                        + action
        );
    }

    private void stopActionbar() {
        if (actionbarTask != null) {
            actionbarTask.cancel();
            actionbarTask = null;
        }
    }

    private void ensureDataFile() {
        if (!core.getDataFolder().exists()
                && !core.getDataFolder().mkdirs()
                && !core.getDataFolder().isDirectory()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data directory"
            );
        }

        if (!file.exists()) {
            core.saveResource("vanish.yml", false);
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize vanish.yml"
            );
        }
    }

    private record RuntimeState(
            boolean collidable,
            boolean canPickupItems,
            boolean silent,
            boolean invulnerable
    ) {
    }
}
