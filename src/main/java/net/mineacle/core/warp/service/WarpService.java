package net.mineacle.core.warp.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warp.model.WarpPoint;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class WarpService {

    private static final int HARD_MAX_ACTIVE = 128;
    private static final int HARD_MAX_QUEUED = 4096;
    private static final int HARD_MAX_STARTS_PER_TICK = 16;
    private static final int HARD_MAX_WAIT_SECONDS = 120;
    private static final int HARD_MAX_AGING_POINTS_PER_SECOND = 100;
    private static final int HARD_MAX_AGING_BONUS = 1000;

    private static final List<String> PRIORITY_ORDER =
            List.of(
                    "developer",
                    "admin",
                    "media-plus",
                    "plus"
            );

    private final Core core;
    private final File file;
    private final LuckPerms luckPerms;
    private FileConfiguration config;

    public WarpService(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "warps.yml"
        );
        this.luckPerms =
                Bukkit.getServicesManager()
                        .load(LuckPerms.class);
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()
                && !core.getDataFolder().mkdirs()
                && !core.getDataFolder().isDirectory()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data directory"
            );
        }

        if (!file.exists()) {
            core.saveResource(
                    "warps.yml",
                    false
            );
        }

        config = YamlConfiguration.loadConfiguration(
                file
        );
    }

    private FileConfiguration config() {
        if (config == null) {
            reload();
        }

        return config;
    }

    public String noPermissionMessage() {
        return colorMessage(
                "messages.no-permission",
                "&cYou do not have permission"
        );
    }

    public String notFoundMessage(String name) {
        return colorMessage(
                "messages.not-found",
                "&cThat warp does not exist"
        ).replace(
                "%warp%",
                safe(name)
        );
    }

    public String setMessage(String name) {
        return colorMessage(
                "messages.set",
                "&aWarp &#8436FE%warp% &#bbbbbbset"
        ).replace(
                "%warp%",
                safe(name)
        );
    }

    public String deletedMessage(String name) {
        return colorMessage(
                "messages.deleted",
                "&cWarp %warp% deleted"
        ).replace(
                "%warp%",
                safe(name)
        );
    }

    public String queueMessage(
            String key,
            String fallback,
            String warpName
    ) {
        return colorMessage(
                "messages." + key,
                fallback
        ).replace(
                "%warp%",
                safe(warpName)
        );
    }

    public List<String> spawnWorlds() {
        List<String> worlds =
                config().getStringList(
                        "spawn-worlds"
                );

        if (worlds.isEmpty()) {
            return List.of(
                    "spawn1",
                    "spawn2",
                    "spawn3"
            );
        }

        return List.copyOf(worlds);
    }

    public boolean isSpawnWorld(
            String worldName
    ) {
        if (worldName == null) {
            return false;
        }

        for (String world : spawnWorlds()) {
            if (world.equalsIgnoreCase(
                    worldName
            )) {
                return true;
            }
        }

        return false;
    }

    public boolean isSpawnWorld(
            Player player
    ) {
        return player != null
                && isSpawnWorld(
                player.getWorld().getName()
        );
    }

    public boolean cancelOnMove() {
        return config().getBoolean(
                "teleport.cancel-on-move",
                true
        );
    }

    public WarpProfile profile(
            Player player
    ) {
        if (player == null) {
            return profileFor("default");
        }

        if (player.isOp()) {
            return profileFor("developer");
        }

        for (String group : PRIORITY_ORDER) {
            if (matchesGroup(
                    player,
                    group
            )) {
                return profileFor(group);
            }
        }

        return profileFor("default");
    }

    public QueueSettings queueSettings() {
        return new QueueSettings(
                config().getBoolean(
                        "teleport.queue.enabled",
                        true
                ),
                Math.clamp(
                        config().getInt(
                                "teleport.queue.max-active",
                                32
                        ),
                        1,
                        HARD_MAX_ACTIVE
                ),
                Math.clamp(
                        config().getInt(
                                "teleport.queue.max-queued",
                                4096
                        ),
                        1,
                        HARD_MAX_QUEUED
                ),
                Math.clamp(
                        config().getInt(
                                "teleport.queue.max-starts-per-tick",
                                2
                        ),
                        1,
                        HARD_MAX_STARTS_PER_TICK
                ),
                Math.clamp(
                        config().getInt(
                                "teleport.queue.max-wait-seconds",
                                30
                        ),
                        5,
                        HARD_MAX_WAIT_SECONDS
                ),
                Math.clamp(
                        config().getInt(
                                "teleport.queue.aging-points-per-second",
                                25
                        ),
                        0,
                        HARD_MAX_AGING_POINTS_PER_SECOND
                ),
                Math.clamp(
                        config().getInt(
                                "teleport.queue.max-aging-bonus",
                                500
                        ),
                        0,
                        HARD_MAX_AGING_BONUS
                )
        );
    }

    public Location targetLocation(
            Player player,
            WarpPoint point
    ) {
        if (player == null || point == null) {
            return null;
        }

        World world;

        if (isSpawnWorld(player)) {
            world = player.getWorld();
        } else {
            world = randomLoadedSpawnWorld()
                    .orElse(null);
        }

        if (world == null) {
            return null;
        }

        return new Location(
                world,
                point.x(),
                point.y(),
                point.z(),
                point.yaw(),
                point.pitch()
        );
    }

    public Optional<World> randomLoadedSpawnWorld() {
        List<World> loaded =
                new ArrayList<>();

        for (String worldName : spawnWorlds()) {
            World world =
                    Bukkit.getWorld(worldName);

            if (world != null) {
                loaded.add(world);
            }
        }

        if (loaded.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                loaded.get(
                        ThreadLocalRandom.current()
                                .nextInt(
                                        loaded.size()
                                )
                )
        );
    }

    public WarpPoint warp(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String key =
                input.toLowerCase(Locale.ROOT);
        ConfigurationSection section =
                config().getConfigurationSection(
                        "warps." + key
                );

        if (section == null) {
            return null;
        }

        Material material =
                Material.matchMaterial(
                        section.getString(
                                "material",
                                "ENDER_PEARL"
                        )
                );

        if (material == null) {
            material = Material.ENDER_PEARL;
        }

        return new WarpPoint(
                key,
                section.getString(
                        "display-name",
                        "&#8436FE" + key
                ),
                material,
                section.getInt(
                        "slot",
                        13
                ),
                section.getDouble(
                        "x",
                        0.5D
                ),
                section.getDouble(
                        "y",
                        65.0D
                ),
                section.getDouble(
                        "z",
                        0.5D
                ),
                (float) section.getDouble(
                        "yaw",
                        0.0D
                ),
                (float) section.getDouble(
                        "pitch",
                        0.0D
                )
        );
    }

    public List<String> warpKeys(
            String partial
    ) {
        ConfigurationSection section =
                config().getConfigurationSection(
                        "warps"
                );

        if (section == null) {
            return Collections.emptyList();
        }

        String filter = partial == null
                ? ""
                : partial.toLowerCase(
                        Locale.ROOT
                );
        List<String> keys =
                new ArrayList<>();

        for (String key :
                section.getKeys(false)) {
            if (filter.isBlank()
                    || key.toLowerCase(
                    Locale.ROOT
            ).startsWith(filter)) {
                keys.add(key);
            }
        }

        keys.sort(
                String.CASE_INSENSITIVE_ORDER
        );
        return List.copyOf(keys);
    }

    public void setWarp(
            String key,
            Player player,
            int slot,
            String displayName
    ) throws IOException {
        String normalized =
                key.toLowerCase(Locale.ROOT);
        Location location =
                player.getLocation();
        String path =
                "warps." + normalized;

        config().set(
                path + ".display-name",
                displayName == null
                        || displayName.isBlank()
                        ? "&#8436FE" + normalized
                        : displayName
        );
        config().set(
                path + ".material",
                "ENDER_PEARL"
        );
        config().set(
                path + ".slot",
                slot
        );
        config().set(
                path + ".x",
                location.getX()
        );
        config().set(
                path + ".y",
                location.getY()
        );
        config().set(
                path + ".z",
                location.getZ()
        );
        config().set(
                path + ".yaw",
                location.getYaw()
        );
        config().set(
                path + ".pitch",
                location.getPitch()
        );

        save();
    }

    public boolean deleteWarp(
            String key
    ) throws IOException {
        String normalized =
                key.toLowerCase(Locale.ROOT);

        if (!config().contains(
                "warps." + normalized
        )) {
            return false;
        }

        config().set(
                "warps." + normalized,
                null
        );
        save();
        return true;
    }

    private WarpProfile profileFor(
            String group
    ) {
        String path =
                "teleport.groups."
                        + group;
        int fallbackDelay =
                group.equals("default")
                        ? config().getInt(
                        "teleport.default-delay-seconds",
                        5
                )
                        : config().getInt(
                        "teleport.plus-delay-seconds",
                        3
                );
        int fallbackPriority =
                switch (group) {
                    case "developer" -> 400;
                    case "admin" -> 300;
                    case "media-plus" -> 200;
                    case "plus" -> 100;
                    default -> 0;
                };

        return new WarpProfile(
                group,
                Math.max(
                        0,
                        config().getInt(
                                path + ".delay-seconds",
                                fallbackDelay
                        )
                ),
                config().getInt(
                        path + ".priority",
                        fallbackPriority
                )
        );
    }

    private boolean matchesGroup(
            Player player,
            String group
    ) {
        List<String> permissions =
                configuredOrDefault(
                        "teleport.groups."
                                + group
                                + ".permissions",
                        defaultPermissions(group)
                );

        for (String permission : permissions) {
            if (!permission.isBlank()
                    && player.hasPermission(
                    permission
            )) {
                return true;
            }
        }

        String primaryGroup =
                primaryGroup(player);

        if (primaryGroup.isBlank()) {
            return false;
        }

        List<String> primaryGroups =
                configuredOrDefault(
                        "teleport.groups."
                                + group
                                + ".primary-groups",
                        defaultPrimaryGroups(group)
                );

        for (String candidate : primaryGroups) {
            if (candidate.equalsIgnoreCase(
                    primaryGroup
            )) {
                return true;
            }
        }

        return false;
    }

    private String primaryGroup(
            Player player
    ) {
        if (luckPerms == null) {
            return "";
        }

        User user =
                luckPerms.getUserManager()
                        .getUser(
                                player.getUniqueId()
                        );

        return user == null
                ? ""
                : user.getPrimaryGroup();
    }

    private List<String> configuredOrDefault(
            String path,
            List<String> fallback
    ) {
        List<String> configured =
                config().getStringList(path);

        return configured.isEmpty()
                ? fallback
                : List.copyOf(configured);
    }

    private List<String> defaultPermissions(
            String group
    ) {
        return switch (group) {
            case "developer" ->
                    List.of(
                            "mineacle.developer"
                    );
            case "admin" ->
                    List.of(
                            "mineaclewarps.admin",
                            "mineacle.admin"
                    );
            case "media-plus" ->
                    List.of(
                            "mineacle.media-plus",
                            "mineacle.mediaplus"
                    );
            case "plus" ->
                    List.of(
                            "mineacle.plus"
                    );
            default ->
                    List.of();
        };
    }

    private List<String> defaultPrimaryGroups(
            String group
    ) {
        return switch (group) {
            case "developer" ->
                    List.of(
                            "developer",
                            "dev"
                    );
            case "admin" ->
                    List.of(
                            "admin",
                            "administrator"
                    );
            case "media-plus" ->
                    List.of(
                            "media-plus",
                            "mediaplus",
                            "media_plus",
                            "media+"
                    );
            case "plus" ->
                    List.of("plus");
            default ->
                    List.of();
        };
    }

    private String colorMessage(
            String path,
            String fallback
    ) {
        return TextColor.color(
                config().getString(
                        path,
                        fallback
                )
        );
    }

    private String safe(String value) {
        return value == null
                ? ""
                : value;
    }

    private void save()
            throws IOException {
        config().save(file);
    }

    public record WarpProfile(
            String group,
            int delaySeconds,
            int priority
    ) {
    }

    public record QueueSettings(
            boolean enabled,
            int maxActive,
            int maxQueued,
            int maxStartsPerTick,
            int maxWaitSeconds,
            int agingPointsPerSecond,
            int maxAgingBonus
    ) {
    }
}
