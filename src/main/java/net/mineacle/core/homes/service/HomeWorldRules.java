package net.mineacle.core.homes.service;

import net.mineacle.core.Core;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;

public final class HomeWorldRules {

    private final Core core;

    public HomeWorldRules(Core core) {
        this.core = core;
    }

    public boolean isBlockedWorld(Location location) {
        return isBlocked(location, "homes.allowed-worlds", "homes.blocked-worlds");
    }

    public boolean isTeamHomeBlockedWorld(Location location) {
        return isBlocked(location, "homes.team-home.allowed-worlds", "homes.team-home.blocked-worlds");
    }

    private boolean isBlocked(Location location, String allowedPath, String blockedPath) {
        if (location == null) {
            return true;
        }

        World world = location.getWorld();
        if (world == null) {
            return true;
        }

        String worldName = world.getName();

        boolean allowedListEnabled = core.getConfig().getBoolean(allowedPath + ".enabled", false);
        List<String> allowedWorlds = core.getConfig().getStringList(allowedPath + ".worlds");

        if (allowedListEnabled && !containsIgnoreCase(allowedWorlds, worldName)) {
            return true;
        }

        List<String> blockedWorlds = core.getConfig().getStringList(blockedPath);
        return containsIgnoreCase(blockedWorlds, worldName);
    }

    private boolean containsIgnoreCase(List<String> list, String value) {
        for (String entry : list) {
            if (entry.equalsIgnoreCase(value)) {
                return true;
            }
        }

        return false;
    }
}