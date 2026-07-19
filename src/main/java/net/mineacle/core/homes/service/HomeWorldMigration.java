package net.mineacle.core.homes.service;

import net.mineacle.core.Core;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class HomeWorldMigration {

    private static final String[] HOME_WORLD_LIST_PATHS = {
            "homes.allowed-worlds.worlds",
            "homes.blocked-worlds",
            "homes.team-home.allowed-worlds.worlds",
            "homes.team-home.blocked-worlds"
    };

    private HomeWorldMigration() {
    }

    public static MigrationResult migrateAll(Core core) {
        if (core == null) {
            return new MigrationResult(0, 0, 0);
        }

        int personalHomes = migratePersonalHomes(core);
        int teamHomes = migrateTeamHomes(core);
        int configEntries = migrateConfigWorldLists(core);

        if (personalHomes > 0
                || teamHomes > 0
                || configEntries > 0) {
            core.getLogger().info(
                    "Migrated legacy home world names: "
                            + personalHomes
                            + " personal home(s), "
                            + teamHomes
                            + " team home(s), "
                            + configEntries
                            + " configuration value(s)"
            );
        }

        return new MigrationResult(
                personalHomes,
                teamHomes,
                configEntries
        );
    }

    public static boolean migratePersonalHome(
            Core core,
            String path
    ) {
        if (core == null || path == null || path.isBlank()) {
            return false;
        }

        FileConfiguration homes = core.getHomesConfig();
        String worldPath = path + ".world";
        String stored = homes.getString(worldPath);

        if (!HomeWorldNames.isLegacy(stored)) {
            return false;
        }

        backupOnce(
                core,
                "homes.yml",
                "homes.yml.pre-overworld-migration.bak"
        );
        homes.set(worldPath, HomeWorldNames.canonical(stored));
        core.saveHomesFile();
        return true;
    }

    public static boolean migrateTeamHome(
            Core core,
            String teamId
    ) {
        if (core == null || teamId == null || teamId.isBlank()) {
            return false;
        }

        FileConfiguration teams = core.getTeamsConfig();
        String worldPath = "team-homes."
                + teamId
                + ".world";
        String stored = teams.getString(worldPath);

        if (!HomeWorldNames.isLegacy(stored)) {
            return false;
        }

        backupOnce(
                core,
                "teams.yml",
                "teams.yml.pre-overworld-migration.bak"
        );
        teams.set(worldPath, HomeWorldNames.canonical(stored));
        core.saveTeamsFile();
        return true;
    }

    private static int migratePersonalHomes(Core core) {
        FileConfiguration homes = core.getHomesConfig();
        ConfigurationSection players =
                homes.getConfigurationSection("homes");

        if (players == null) {
            return 0;
        }

        List<String> worldPaths = new ArrayList<>();

        for (String playerId : players.getKeys(false)) {
            ConfigurationSection playerHomes =
                    players.getConfigurationSection(playerId);

            if (playerHomes == null) {
                continue;
            }

            for (String homeId : playerHomes.getKeys(false)) {
                String worldPath = "homes."
                        + playerId
                        + "."
                        + homeId
                        + ".world";
                String stored = homes.getString(worldPath);

                if (HomeWorldNames.isLegacy(stored)) {
                    worldPaths.add(worldPath);
                }
            }
        }

        if (worldPaths.isEmpty()) {
            return 0;
        }

        backupOnce(
                core,
                "homes.yml",
                "homes.yml.pre-overworld-migration.bak"
        );

        for (String worldPath : worldPaths) {
            homes.set(
                    worldPath,
                    HomeWorldNames.canonical(
                            homes.getString(worldPath)
                    )
            );
        }

        core.saveHomesFile();
        return worldPaths.size();
    }

    private static int migrateTeamHomes(Core core) {
        FileConfiguration teams = core.getTeamsConfig();
        ConfigurationSection teamHomes =
                teams.getConfigurationSection("team-homes");

        if (teamHomes == null) {
            return 0;
        }

        List<String> worldPaths = new ArrayList<>();

        for (String teamId : teamHomes.getKeys(false)) {
            String worldPath = "team-homes."
                    + teamId
                    + ".world";
            String stored = teams.getString(worldPath);

            if (HomeWorldNames.isLegacy(stored)) {
                worldPaths.add(worldPath);
            }
        }

        if (worldPaths.isEmpty()) {
            return 0;
        }

        backupOnce(
                core,
                "teams.yml",
                "teams.yml.pre-overworld-migration.bak"
        );

        for (String worldPath : worldPaths) {
            teams.set(
                    worldPath,
                    HomeWorldNames.canonical(
                            teams.getString(worldPath)
                    )
            );
        }

        core.saveTeamsFile();
        return worldPaths.size();
    }

    private static int migrateConfigWorldLists(Core core) {
        FileConfiguration config = core.getConfig();
        int changedEntries = 0;
        boolean changed = false;

        for (String path : HOME_WORLD_LIST_PATHS) {
            List<String> original = config.getStringList(path);

            if (original.isEmpty()) {
                continue;
            }

            List<String> migrated = migrateList(original);

            if (!migrated.equals(original)) {
                changedEntries += countDifferences(
                        original,
                        migrated
                );
                config.set(path, migrated);
                changed = true;
            }
        }

        if (!changed) {
            return 0;
        }

        backupOnce(
                core,
                "config.yml",
                "config.yml.pre-overworld-migration.bak"
        );
        core.saveConfig();
        return Math.max(1, changedEntries);
    }

    private static List<String> migrateList(List<String> original) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> migrated = new ArrayList<>();

        for (String entry : original) {
            if (entry == null || entry.isBlank()) {
                continue;
            }

            String canonical = HomeWorldNames.canonical(entry);
            String key = canonical.toLowerCase(
                    java.util.Locale.ROOT
            );

            if (seen.add(key)) {
                migrated.add(canonical);
            }
        }

        return List.copyOf(migrated);
    }

    private static int countDifferences(
            List<String> original,
            List<String> migrated
    ) {
        int maximum = Math.max(
                original.size(),
                migrated.size()
        );
        int differences = 0;

        for (int index = 0; index < maximum; index++) {
            String before = index < original.size()
                    ? original.get(index)
                    : null;
            String after = index < migrated.size()
                    ? migrated.get(index)
                    : null;

            if (before == null
                    ? after != null
                    : !before.equals(after)) {
                differences++;
            }
        }

        return differences;
    }

    private static void backupOnce(
            Core core,
            String sourceName,
            String backupName
    ) {
        File source = new File(
                core.getDataFolder(),
                sourceName
        );
        File backup = new File(
                core.getDataFolder(),
                backupName
        );

        if (!source.isFile() || backup.exists()) {
            return;
        }

        try {
            Files.copy(
                    source.toPath(),
                    backup.toPath(),
                    StandardCopyOption.COPY_ATTRIBUTES
            );
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not create migration backup "
                            + backupName,
                    exception
            );
        }
    }

    public record MigrationResult(
            int personalHomes,
            int teamHomes,
            int configEntries
    ) {
    }
}
