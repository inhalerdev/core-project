package net.mineacle.core.homes.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.model.HomeRecord;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HomeService {

    private static final int ACTIVE_MAX_HOMES = 3;
    private static final int LEGACY_STORAGE_MAX_HOMES = 5;

    private final Core core;

    public HomeService(Core core) {
        this.core = core;
        HomeWorldMigration.migrateAll(core);
    }

    public int getMaxHomes(Player player) {
        if (player == null) {
            return 0;
        }

        FileConfiguration config = core.getConfig();
        int defaultMax = Math.clamp(
                config.getInt(
                        "homes.max-homes.default",
                        2
                ),
                0,
                ACTIVE_MAX_HOMES
        );
        int plusMax = Math.clamp(
                Math.max(
                        defaultMax,
                        config.getInt(
                                "homes.max-homes.plus",
                                3
                        )
                ),
                0,
                ACTIVE_MAX_HOMES
        );
        String plusPermission = config.getString(
                "homes.plus-permission",
                "mineacle.plus"
        );

        int configuredMax = !plusPermission.isBlank()
                && player.hasPermission(plusPermission)
                ? plusMax
                : defaultMax;

        return Math.clamp(
                configuredMax,
                0,
                ACTIVE_MAX_HOMES
        );
    }

    /**
     * Home limits are slot entitlements, not merely a count limit.
     * Default players own slots 1..2 and Mineacle+ owns slots 1..3.
     * Stored data in a currently locked slot is intentionally preserved.
     */
    public boolean slotLocked(Player player, int id) {
        return player == null
                || invalidActiveId(id)
                || id > getMaxHomes(player);
    }

    public boolean personalHomeSetBlocked(Player player) {
        return player == null
                || personalHomeWorldBlocked(player.getWorld());
    }

    public boolean teamHomeSetBlocked(Player player) {
        return player == null
                || teamHomeWorldBlocked(player.getWorld());
    }

    public boolean personalHomeWorldBlocked(World world) {
        if (world == null) {
            return true;
        }

        String worldName = world.getName();

        if (isListedWorld(
                "homes.blocked-worlds",
                worldName
        )) {
            return true;
        }

        if (!core.getConfig().getBoolean(
                "homes.allowed-worlds.enabled",
                true
        )) {
            return false;
        }

        return !isListedWorld(
                "homes.allowed-worlds.worlds",
                worldName
        );
    }

    public boolean teamHomeWorldBlocked(World world) {
        if (world == null) {
            return true;
        }

        String worldName = world.getName();

        if (isListedWorld(
                "homes.team-home.blocked-worlds",
                worldName
        )) {
            return true;
        }

        if (!core.getConfig().getBoolean(
                "homes.team-home.allowed-worlds.enabled",
                true
        )) {
            return false;
        }

        return !isListedWorld(
                "homes.team-home.allowed-worlds.worlds",
                worldName
        );
    }

    /**
     * A home exists when its data is stored, even if its world is currently
     * unavailable. World resolution must not make a saved slot appear empty.
     */
    public boolean exists(UUID playerId, int id) {
        if (invalidStoredId(id) || playerId == null) {
            return false;
        }

        FileConfiguration homes = core.getHomesConfig();
        String base = path(playerId, id);
        String world = homes.getString(base + ".world");

        return homes.isConfigurationSection(base)
                && world != null
                && !world.isBlank();
    }

    public Location get(UUID playerId, int id) {
        if (!exists(playerId, id)) {
            return null;
        }

        String base = path(playerId, id);
        FileConfiguration homes = core.getHomesConfig();
        String storedWorld = homes.getString(base + ".world");
        String canonicalWorld = HomeWorldNames.canonical(storedWorld);

        if (HomeWorldNames.isLegacy(storedWorld)) {
            HomeWorldMigration.migratePersonalHome(
                    core,
                    base
            );
        }

        HomeRecord record = new HomeRecord(
                canonicalWorld,
                homes.getDouble(base + ".x"),
                homes.getDouble(base + ".y"),
                homes.getDouble(base + ".z"),
                (float) homes.getDouble(base + ".yaw"),
                (float) homes.getDouble(base + ".pitch")
        );

        return record.toLocation();
    }

    public void set(
            UUID playerId,
            int id,
            Location location
    ) {
        set(
                playerId,
                id,
                location,
                getDefaultDisplayName(id)
        );
    }

    public void set(
            UUID playerId,
            int id,
            Location location,
            String displayName
    ) {
        if (playerId == null
                || invalidActiveId(id)
                || location == null
                || location.getWorld() == null) {
            throw new IllegalArgumentException(
                    "Cannot save an invalid home"
            );
        }

        String base = path(playerId, id);
        FileConfiguration homes = core.getHomesConfig();
        HomeRecord record = HomeRecord.fromLocation(location);

        homes.set(base + ".world", record.worldName());
        homes.set(base + ".x", record.x());
        homes.set(base + ".y", record.y());
        homes.set(base + ".z", record.z());
        homes.set(base + ".yaw", record.yaw());
        homes.set(base + ".pitch", record.pitch());
        homes.set(
                base + ".name",
                sanitizeName(displayName, id)
        );

        core.saveHomesFile();
    }

    public void rename(
            UUID playerId,
            int id,
            String newName
    ) {
        if (!exists(playerId, id)) {
            return;
        }

        core.getHomesConfig().set(
                path(playerId, id) + ".name",
                sanitizeName(newName, id)
        );
        core.saveHomesFile();
    }

    public void delete(UUID playerId, int id) {
        if (playerId == null || invalidStoredId(id)) {
            return;
        }

        core.getHomesConfig().set(
                path(playerId, id),
                null
        );
        core.saveHomesFile();
    }

    public String getDisplayName(UUID playerId, int id) {
        String stored = core.getHomesConfig().getString(
                path(playerId, id) + ".name"
        );

        if (stored == null || stored.isBlank()) {
            return getDefaultDisplayName(id);
        }

        String safe = safeDisplayName(stored);

        return safe.isBlank()
                ? getDefaultDisplayName(id)
                : safe;
    }

    public String getDefaultDisplayName(int id) {
        return core.getConfig().getString(
                "homes.default-name-format",
                "Home %id%"
        ).replace("%id%", String.valueOf(id));
    }

    public Integer findHomeIdByName(
            UUID playerId,
            int maxHomes,
            String input
    ) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String trimmed = input.trim();
        int maximum = Math.clamp(
                maxHomes,
                0,
                LEGACY_STORAGE_MAX_HOMES
        );

        for (int id = 1; id <= maximum; id++) {
            if (exists(playerId, id)
                    && getDisplayName(playerId, id)
                    .equalsIgnoreCase(trimmed)) {
                return id;
            }
        }

        try {
            int parsed = Integer.parseInt(trimmed);

            if (parsed >= 1
                    && parsed <= maximum
                    && exists(playerId, parsed)) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }

        return null;
    }

    public Integer findFirstEmptySlot(Player player) {
        if (player == null) {
            return null;
        }

        UUID playerId = player.getUniqueId();
        int maximum = getMaxHomes(player);

        for (int id = 1; id <= maximum; id++) {
            if (!exists(playerId, id)) {
                return id;
            }
        }

        return null;
    }

    public Integer findByName(
            UUID playerId,
            int maxHomes,
            String name
    ) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String trimmed = name.trim();
        int maximum = Math.clamp(
                maxHomes,
                0,
                LEGACY_STORAGE_MAX_HOMES
        );

        for (int id = 1; id <= maximum; id++) {
            if (exists(playerId, id)
                    && getDisplayName(playerId, id)
                    .equalsIgnoreCase(trimmed)) {
                return id;
            }
        }

        return null;
    }

    /**
     * Searches every physical slot, including currently locked Mineacle+
     * slots. This prevents creating duplicate names while paid-slot data is
     * preserved for a player whose entitlement temporarily changed.
     */
    public Integer findAnyByName(UUID playerId, String name) {
        return findByName(
                playerId,
                LEGACY_STORAGE_MAX_HOMES,
                name
        );
    }

    public List<String> getSavedHomeNames(Player player) {
        if (player == null) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        UUID playerId = player.getUniqueId();
        int maximum = getMaxHomes(player);

        for (int id = 1; id <= maximum; id++) {
            if (exists(playerId, id)) {
                names.add(getDisplayName(playerId, id));
            }
        }

        return List.copyOf(names);
    }

    public boolean invalidName(String name) {
        if (name == null) {
            return true;
        }

        String trimmed = name.trim();

        if (trimmed.isBlank()
                || trimmed.length() > 24
                || TextColor.containsFormatting(trimmed)) {
            return true;
        }

        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isISOControl(trimmed.charAt(index))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Keeps labels unique and reserves Home 1/2/3 for their matching slots.
     * This prevents ambiguous command targets after a custom rename.
     */
    public boolean nameUnavailableForSlot(
            UUID playerId,
            int targetId,
            String name
    ) {
        if (playerId == null
                || invalidActiveId(targetId)
                || invalidName(name)) {
            return true;
        }

        String candidate = name.trim();

        for (int id = 1; id <= ACTIVE_MAX_HOMES; id++) {
            if (id != targetId
                    && getDefaultDisplayName(id)
                    .equalsIgnoreCase(candidate)) {
                return true;
            }
        }

        for (int id = 1; id <= LEGACY_STORAGE_MAX_HOMES; id++) {
            if (id != targetId
                    && exists(playerId, id)
                    && getDisplayName(playerId, id)
                    .equalsIgnoreCase(candidate)) {
                return true;
            }
        }

        return false;
    }

    public String sanitizeName(
            String name,
            int fallbackId
    ) {
        if (invalidName(name)) {
            return getDefaultDisplayName(fallbackId);
        }

        return name.trim();
    }

    public boolean personalHomeTeleportBlocked(Location location) {
        return invalidTeleportLocation(location)
                || personalHomeWorldBlocked(location.getWorld());
    }

    public boolean teamHomeTeleportBlocked(Location location) {
        return invalidTeleportLocation(location)
                || teamHomeWorldBlocked(location.getWorld());
    }

    private boolean invalidTeleportLocation(Location location) {
        if (location == null
                || !location.isFinite()
                || !location.isWorldLoaded()) {
            return true;
        }

        World world;

        try {
            world = location.getWorld();
        } catch (IllegalArgumentException ignored) {
            return true;
        }

        if (world == null) {
            return true;
        }

        int blockY = location.getBlockY();

        return blockY < world.getMinHeight()
                || blockY >= world.getMaxHeight()
                || !world.getWorldBorder().isInside(location);
    }

    private String safeDisplayName(String stored) {
        String trimmed = stored == null
                ? ""
                : stored.trim();

        if (trimmed.isBlank()) {
            return "";
        }

        String safe = TextColor.containsFormatting(trimmed)
                ? TextColor.strip(trimmed)
                : trimmed;

        StringBuilder output = new StringBuilder(
                Math.min(24, safe.length())
        );

        for (int index = 0;
             index < safe.length() && output.length() < 24;
             index++) {
            char character = safe.charAt(index);

            if (!Character.isISOControl(character)) {
                output.append(character);
            }
        }

        return output.toString().trim();
    }

    private boolean isListedWorld(
            String path,
            String worldName
    ) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        for (String listedWorld
                : core.getConfig().getStringList(path)) {
            if (HomeWorldNames.sameWorldName(
                    listedWorld,
                    worldName
            )) {
                return true;
            }
        }

        return false;
    }

    private boolean invalidActiveId(int id) {
        return id < 1 || id > ACTIVE_MAX_HOMES;
    }

    private boolean invalidStoredId(int id) {
        return id < 1 || id > LEGACY_STORAGE_MAX_HOMES;
    }

    private String path(UUID playerId, int id) {
        return "homes." + playerId + "." + id;
    }
}
