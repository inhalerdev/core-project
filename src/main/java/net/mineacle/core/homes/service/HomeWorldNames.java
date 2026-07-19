package net.mineacle.core.homes.service;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Locale;

public final class HomeWorldNames {

    private HomeWorldNames() {
    }

    public static String canonical(String rawWorldName) {
        if (rawWorldName == null) {
            return null;
        }

        String worldName = rawWorldName.trim();

        if (worldName.isEmpty()) {
            return worldName;
        }

        return switch (worldName.toLowerCase(Locale.ROOT)) {
            case "origins" -> "overworld";
            case "origins_nether" -> "overworld_nether";
            case "origins_the_end" -> "overworld_the_end";
            default -> worldName;
        };
    }

    public static World resolve(String storedWorldName) {
        if (storedWorldName == null
                || storedWorldName.isBlank()) {
            return null;
        }

        String canonical = canonical(storedWorldName);
        World world = Bukkit.getWorld(canonical);

        /*
         * The fallback keeps the code safe during a staged server rename.
         * Stored data is still migrated to the canonical name separately.
         */
        if (world == null
                && !canonical.equalsIgnoreCase(storedWorldName)) {
            world = Bukkit.getWorld(storedWorldName);
        }

        return world;
    }

    public static boolean sameWorldName(
            String first,
            String second
    ) {
        String canonicalFirst = canonical(first);
        String canonicalSecond = canonical(second);

        return canonicalFirst != null
                && canonicalSecond != null
                && canonicalFirst.equalsIgnoreCase(canonicalSecond);
    }

    public static boolean isLegacy(String rawWorldName) {
        if (rawWorldName == null || rawWorldName.isBlank()) {
            return false;
        }

        return !canonical(rawWorldName)
                .equalsIgnoreCase(rawWorldName.trim());
    }
}
