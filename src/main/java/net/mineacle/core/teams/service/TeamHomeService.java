package net.mineacle.core.teams.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

public final class TeamHomeService {

    private final Core core;
    private final TeamService teamService;

    public TeamHomeService(Core core, TeamService teamService) {
        this.core = core;
        this.teamService = teamService;
    }

    public boolean hasTeamHome(String teamId) {
        return getTeamHome(teamId) != null;
    }

    public Location getTeamHome(String teamId) {
        FileConfiguration config = core.getTeamsConfig();
        String path = "team-homes." + teamId;

        String worldName = config.getString(path + ".world", null);
        if (worldName == null) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        double x = config.getDouble(path + ".x");
        double y = config.getDouble(path + ".y");
        double z = config.getDouble(path + ".z");
        float yaw = (float) config.getDouble(path + ".yaw");
        float pitch = (float) config.getDouble(path + ".pitch");

        return new Location(world, x, y, z, yaw, pitch);
    }

    public void setTeamHome(String teamId, Location location) {
        FileConfiguration config = core.getTeamsConfig();
        String path = "team-homes." + teamId;

        config.set(path + ".world", location.getWorld() == null ? "world" : location.getWorld().getName());
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

        core.getTeamsConfig().set("team-homes." + teamId, null);
        core.saveTeamsFile();
        return true;
    }
}