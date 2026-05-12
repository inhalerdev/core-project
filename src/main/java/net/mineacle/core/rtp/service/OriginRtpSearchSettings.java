package net.mineacle.core.rtp.service;

import net.mineacle.core.Core;
import org.bukkit.Material;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public record OriginRtpSearchSettings(
        String rtpKey,
        String displayName,
        String worldName,
        boolean useWorldBorder,
        int centerX,
        int centerZ,
        int minRadius,
        int maxRadius,
        String shape,
        int minY,
        int maxY,
        int maxAttempts,
        int preloadRadius,
        Set<Material> unsafeBlocks
) {

    public static OriginRtpSearchSettings fromConfig(Core core, String rtpKey) {
        String key = normalizeKey(rtpKey);
        String basePath = "origin-rtp.destinations." + key;

        Set<Material> unsafeBlocks = new HashSet<>();

        for (String rawMaterial : core.getConfig().getStringList(basePath + ".unsafe-blocks")) {
            addUnsafeBlock(core, unsafeBlocks, rawMaterial);
        }

        if (unsafeBlocks.isEmpty()) {
            for (String rawMaterial : core.getConfig().getStringList("origin-rtp.unsafe-blocks")) {
                addUnsafeBlock(core, unsafeBlocks, rawMaterial);
            }
        }

        if (unsafeBlocks.isEmpty()) {
            unsafeBlocks.add(Material.AIR);
            unsafeBlocks.add(Material.CAVE_AIR);
            unsafeBlocks.add(Material.VOID_AIR);
            unsafeBlocks.add(Material.WATER);
            unsafeBlocks.add(Material.LAVA);
            unsafeBlocks.add(Material.FIRE);
            unsafeBlocks.add(Material.SOUL_FIRE);
            unsafeBlocks.add(Material.CACTUS);
            unsafeBlocks.add(Material.MAGMA_BLOCK);
            unsafeBlocks.add(Material.POWDER_SNOW);
            unsafeBlocks.add(Material.BEDROCK);
        }

        int minRadius = Math.max(0, getInt(core, basePath + ".search.min-radius", "origin-rtp.search.min-radius", 250));
        int maxRadius = Math.max(minRadius + 1, getInt(core, basePath + ".search.max-radius", "origin-rtp.search.max-radius", 5000));

        int minY = getInt(core, basePath + ".search.min-y", "origin-rtp.search.min-y", defaultMinY(key));
        int maxY = getInt(core, basePath + ".search.max-y", "origin-rtp.search.max-y", defaultMaxY(key));

        if (maxY <= minY) {
            maxY = minY + 1;
        }

        return new OriginRtpSearchSettings(
                key,
                core.getConfig().getString(basePath + ".display-name", defaultDisplayName(key)),
                core.getConfig().getString(basePath + ".world", defaultWorld(key, core)),
                core.getConfig().getBoolean(basePath + ".search.use-world-border",
                        core.getConfig().getBoolean("origin-rtp.search.use-world-border", true)),
                getInt(core, basePath + ".search.center-x", "origin-rtp.search.center-x", 0),
                getInt(core, basePath + ".search.center-z", "origin-rtp.search.center-z", 0),
                minRadius,
                maxRadius,
                core.getConfig().getString(basePath + ".search.shape",
                        core.getConfig().getString("origin-rtp.search.shape", "square")),
                minY,
                maxY,
                Math.max(1, getInt(core, basePath + ".search.max-attempts", "origin-rtp.search.max-attempts", 128)),
                Math.max(0, getInt(core, basePath + ".search.preload-radius", "origin-rtp.search.preload-radius", 2)),
                unsafeBlocks
        );
    }

    private static int getInt(Core core, String primary, String fallback, int defaultValue) {
        if (core.getConfig().contains(primary)) {
            return core.getConfig().getInt(primary, defaultValue);
        }

        return core.getConfig().getInt(fallback, defaultValue);
    }

    private static void addUnsafeBlock(Core core, Set<Material> unsafeBlocks, String rawMaterial) {
        if (rawMaterial == null || rawMaterial.isBlank()) {
            return;
        }

        try {
            unsafeBlocks.add(Material.valueOf(rawMaterial.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            core.getLogger().warning("Invalid origin-rtp unsafe block: " + rawMaterial);
        }
    }

    private static String normalizeKey(String rtpKey) {
        if (rtpKey == null || rtpKey.isBlank()) {
            return "origins";
        }

        String key = rtpKey.toLowerCase(Locale.ROOT);

        if (key.equals("origin") || key.equals("overworld") || key.equals("world")) {
            return "origins";
        }

        if (key.equals("the_nether")) {
            return "nether";
        }

        if (key.equals("the_end")) {
            return "end";
        }

        return key;
    }

    private static String defaultDisplayName(String key) {
        return switch (key) {
            case "nether" -> "Nether";
            case "end" -> "End";
            default -> "Origins";
        };
    }

    private static String defaultWorld(String key, Core core) {
        return switch (key) {
            case "nether" -> "origins_nether";
            case "end" -> "origins_the_end";
            default -> core.getConfig().getString("origin-rtp.world", "origins");
        };
    }

    private static int defaultMinY(String key) {
        return switch (key) {
            case "nether" -> 32;
            case "end" -> 48;
            default -> 64;
        };
    }

    private static int defaultMaxY(String key) {
        return switch (key) {
            case "nether" -> 118;
            case "end" -> 256;
            default -> 320;
        };
    }
}
