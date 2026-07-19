package net.mineacle.core.teams.service;

import net.mineacle.core.Core;
import net.mineacle.core.homes.service.HomeWorldMigration;
import net.mineacle.core.homes.service.HomeWorldNames;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

public final class TeamHomeService {

    private final Core core;
    private final TeamService teamService;

    public TeamHomeService(
            Core core,
            TeamService teamService
    ) {
        this.core = core;
        this.teamService = teamService;
    }

    /**
     * Team Home existence is based on stored data, not whether the world is
     * currently loaded. A temporary world lookup failure must not make the
     * saved Team Home appear unset.
     */
    public boolean hasTeamHome(String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return false;
        }

        String path = "team-homes." + teamId;
        String world = core.getTeamsConfig().getString(
                path + ".world"
        );

        return core.getTeamsConfig()
                .isConfigurationSection(path)
                && world != null
                && !world.isBlank();
    }

    public Location getTeamHome(String teamId) {
        if (!hasTeamHome(teamId)) {
            return null;
        }

        FileConfiguration config = core.getTeamsConfig();
        String path = "team-homes." + teamId;
        String storedWorld = config.getString(path + ".world");
        String canonicalWorld =
                HomeWorldNames.canonical(storedWorld);

        if (HomeWorldNames.isLegacy(storedWorld)) {
            HomeWorldMigration.migrateTeamHome(
                    core,
                    teamId
            );
        }

        World world = HomeWorldNames.resolve(canonicalWorld);

        if (world == null) {
            return null;
        }

        return new Location(
                world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw"),
                (float) config.getDouble(path + ".pitch")
        );
    }

    public void setTeamHome(
            String teamId,
            Location location
    ) {
        if (teamId == null
                || teamId.isBlank()
                || location == null
                || location.getWorld() == null) {
            throw new IllegalArgumentException(
                    "Cannot save an invalid Team Home"
            );
        }

        FileConfiguration config = core.getTeamsConfig();
        String path = "team-homes." + teamId;

        config.set(
                path + ".world",
                HomeWorldNames.canonical(
                        location.getWorld().getName()
                )
        );
        config.set(path + ".x", location.getX());
        config.set(path + ".y", location.getY());
        config.set(path + ".z", location.getZ());
        config.set(path + ".yaw", location.getYaw());
        config.set(path + ".pitch", location.getPitch());

        core.saveTeamsFile();
    }

    public boolean deleteTeamHome(String teamId) {
        if (!hasTeamHome(teamId)) {
            return false;
        }

        core.getTeamsConfig().set(
                "team-homes." + teamId,
                null
        );
        core.saveTeamsFile();
        return true;
    }
}
