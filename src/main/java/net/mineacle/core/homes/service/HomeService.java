package net.mineacle.core.homes.service;

import net.mineacle.core.Core;
import net.mineacle.core.homes.model.HomeRecord;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HomeService {

    private static final int ABSOLUTE_MAX_HOMES = 5;

    private final Core core;

    public HomeService(Core core) {
        this.core = core;
        HomeWorldMigration.migrateAll(core);
    }

    public int getMaxHomes(Player player) {
        FileConfiguration config = core.getConfig();
        int defaultMax = config.getInt(
                "homes.max-homes.default",
                3
        );
        int plusMax = config.getInt(
                "homes.max-homes.plus",
                5
        );
        String plusPermission = config.getString(
                "homes.plus-permission",
                "mineacle.plus"
        );

        int configuredMax = plusPermission != null
                && !plusPermission.isBlank()
                && player.hasPermission(plusPermission)
                ? plusMax
                : defaultMax;

        return Math.max(
                0,
                Math.min(ABSOLUTE_MAX_HOMES, configuredMax)
        );
    }

    public boolean canSetPersonalHomeHere(Player player) {
        return player != null
                && isWorldAllowedForPersonalHome(
                player.getWorld()
        );
    }

    public boolean canSetTeamHomeHere(Player player) {
        return player != null
                && isWorldAllowedForTeamHome(
                player.getWorld()
        );
    }

    public boolean isWorldAllowedForPersonalHome(World world) {
        if (world == null) {
            return false;
        }

        String worldName = world.getName();

        if (isListedWorld(
                "homes.blocked-worlds",
                worldName
        )) {
            return false;
        }

        if (!core.getConfig().getBoolean(
                "homes.allowed-worlds.enabled",
                true
        )) {
            return true;
        }

        return isListedWorld(
                "homes.allowed-worlds.worlds",
                worldName
        );
    }

    public boolean isWorldAllowedForTeamHome(World world) {
        if (world == null) {
            return false;
        }

        String worldName = world.getName();

        if (isListedWorld(
                "homes.team-home.blocked-worlds",
                worldName
        )) {
            return false;
        }

        if (!core.getConfig().getBoolean(
                "homes.team-home.allowed-worlds.enabled",
                true
        )) {
            return true;
        }

        return isListedWorld(
                "homes.team-home.allowed-worlds.worlds",
                worldName
        );
    }

    public int getUsedHomeCount(UUID playerId) {
        int count = 0;

        for (int id = 1; id <= ABSOLUTE_MAX_HOMES; id++) {
            if (exists(playerId, id)) {
                count++;
            }
        }

        return count;
    }

    public boolean hasFreeHomeCapacity(Player player) {
        return getUsedHomeCount(player.getUniqueId())
                < getMaxHomes(player);
    }

    /**
     * A home exists when its data is stored, even if its world is currently
     * unavailable. World resolution must not make a saved slot appear empty.
     */
    public boolean exists(UUID playerId, int id) {
        if (!validId(id) || playerId == null) {
            return false;
        }

        FileConfiguration homes = core.getHomesConfig();
        String base = path(playerId, id);
        String world = homes.getString(base + ".world");

        return homes.isConfigurationSection(base)
                && world != null
                && !world.isBlank();
    }

    public boolean isWorldAvailable(UUID playerId, int id) {
        if (!exists(playerId, id)) {
            return false;
        }

        return HomeWorldNames.resolve(
                storedWorldName(playerId, id)
        ) != null;
    }

    public String storedWorldName(UUID playerId, int id) {
        if (!validId(id) || playerId == null) {
            return null;
        }

        return core.getHomesConfig().getString(
                path(playerId, id) + ".world"
        );
    }

    public Location get(UUID playerId, int id) {
        if (!exists(playerId, id)) {
            return null;
        }

        String base = path(playerId, id);
        FileConfiguration homes = core.getHomesConfig();
        String storedWorld = homes.getString(base + ".world");
        String canonicalWorld =
                HomeWorldNames.canonical(storedWorld);

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
                || !validId(id)
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
        if (playerId == null || !validId(id)) {
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

        return stored;
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
        int maximum = Math.min(
                ABSOLUTE_MAX_HOMES,
                Math.max(0, maxHomes)
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
        int maximum = Math.min(
                ABSOLUTE_MAX_HOMES,
                Math.max(0, maxHomes)
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

    public List<String> getSavedHomeNames(Player player) {
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

    public boolean isValidName(String name) {
        if (name == null) {
            return false;
        }

        String trimmed = name.trim();

        return !trimmed.isBlank()
                && trimmed.length() <= 24;
    }

    public String sanitizeName(
            String name,
            int fallbackId
    ) {
        if (!isValidName(name)) {
            return getDefaultDisplayName(fallbackId);
        }

        return name.trim();
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

    private boolean validId(int id) {
        return id >= 1 && id <= ABSOLUTE_MAX_HOMES;
    }

    private String path(UUID playerId, int id) {
        return "homes." + playerId + "." + id;
    }
}
