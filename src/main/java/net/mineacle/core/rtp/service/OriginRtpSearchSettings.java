package net.mineacle.core.rtp.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable RTP search policy.
 *
 * The configured values remain the baseline, but a wide live world border
 * receives safer defaults automatically. This lets an existing Mineacle
 * config benefit from the 100k x 100k map profile without forcing server
 * operators to delete or regenerate config.yml.
 */
public record OriginRtpSearchSettings(
        String destination,
        String displayName,
        String worldName,
        boolean useWorldBorder,
        int worldBorderPadding,
        int fallbackCenterX,
        int fallbackCenterZ,
        int fallbackMaximumRadius,
        int minimumDistanceFromWorldSpawn,
        int maximumDistanceFromWorldSpawn,
        int minimumY,
        int maximumY,
        int maximumAttempts,
        int candidatesPerBatch,
        boolean surfaceOnly,
        int safePlatformRadius,
        int maximumGroundHeightDifference,
        int hazardCheckRadius,
        boolean preferUnexploredChunks,
        int candidatePoolMultiplier,
        int recentDestinationHistory,
        int minimumRecentDestinationDistance,
        boolean randomizedVerticalSearch,
        Set<Material> unsafeBlocks
) {

    private static final double WIDE_BORDER_THRESHOLD = 80_000.0D;
    private static final int WIDE_BORDER_EDGE_PADDING = 256;

    public static OriginRtpSearchSettings fromConfig(
            Core core,
            String rawDestination
    ) {
        String destination = canonicalDestination(rawDestination);
        String base = "origin-rtp.destinations." + destination;

        String worldName = canonicalWorld(
                core.getConfig().getString(
                        base + ".world",
                        defaultWorld(destination)
                )
        );

        boolean useWorldBorder = core.getConfig().getBoolean(
                base + ".search.use-world-border",
                core.getConfig().getBoolean(
                        "origin-rtp.search.use-world-border",
                        true
                )
        );

        World world = Bukkit.getWorld(worldName);
        boolean wideBorder = useWorldBorder
                && world != null
                && world.getWorldBorder().getSize()
                >= WIDE_BORDER_THRESHOLD;

        int minimumY = integer(
                core,
                base + ".search.min-y",
                "origin-rtp.search.min-y",
                defaultMinimumY(destination)
        );
        int maximumY = integer(
                core,
                base + ".search.max-y",
                "origin-rtp.search.max-y",
                defaultMaximumY(destination)
        );

        if (maximumY <= minimumY) {
            maximumY = minimumY + 1;
        }

        int configuredMinimumDistance = Math.max(
                0,
                integer(
                        core,
                        base + ".search.minimum-distance-from-world-spawn",
                        "origin-rtp.search.minimum-distance-from-world-spawn",
                        1000
                )
        );

        int minimumDistance = Math.max(
                configuredMinimumDistance,
                Math.max(
                        0,
                        core.getConfig().getInt(
                                base
                                        + ".search.minimum-exploration-distance-from-world-spawn",
                                defaultExplorationDistance(destination)
                        )
                )
        );

        if (wideBorder && destination.equals("overworld")) {
            minimumDistance = Math.max(
                    minimumDistance,
                    2500
            );
        }

        int maximumDistance = Math.max(
                0,
                integer(
                        core,
                        base + ".search.maximum-distance-from-world-spawn",
                        "origin-rtp.search.maximum-distance-from-world-spawn",
                        0
                )
        );

        if (maximumDistance > 0
                && maximumDistance <= minimumDistance) {
            maximumDistance = 0;
        }

        int worldBorderPadding = Math.max(
                0,
                integer(
                        core,
                        base + ".search.world-border-padding",
                        "origin-rtp.search.world-border-padding",
                        32
                )
        );

        if (wideBorder) {
            worldBorderPadding = Math.max(
                    worldBorderPadding,
                    WIDE_BORDER_EDGE_PADDING
            );
        }

        int maximumAttempts = Math.max(
                1,
                integer(
                        core,
                        base + ".search.max-attempts",
                        "origin-rtp.search.max-attempts",
                        160
                )
        );

        if (wideBorder) {
            maximumAttempts = Math.max(
                    maximumAttempts,
                    switch (destination) {
                        case "end" -> 320;
                        case "nether" -> 240;
                        default -> 192;
                    }
            );
        }

        int safePlatformRadius = Math.max(
                0,
                Math.min(
                        2,
                        integer(
                                core,
                                base + ".search.safe-platform-radius",
                                "origin-rtp.search.safe-platform-radius",
                                1
                        )
                )
        );

        /*
         * Nether terrain is naturally enclosed and uneven. Requiring a 3x3
         * platform rejects many otherwise safe fortress/cave/quartz locations.
         * The center block still must be solid and feet/head space must be safe.
         */
        if (wideBorder && destination.equals("nether")) {
            safePlatformRadius = 0;
        }

        int hazardCheckRadius = Math.max(
                0,
                Math.min(
                        4,
                        integer(
                                core,
                                base + ".search.hazard-check-radius",
                                "origin-rtp.search.hazard-check-radius",
                                2
                        )
                )
        );

        if (wideBorder && destination.equals("nether")) {
            hazardCheckRadius = Math.min(
                    hazardCheckRadius,
                    1
            );
        }

        boolean preferUnexplored = destinationBoolean(
                core,
                base + ".search.prefer-unexplored-chunks",
                wideBorder || defaultPreferUnexplored(destination)
        );

        int candidatePoolMultiplier = Math.max(
                1,
                Math.min(
                        16,
                        integer(
                                core,
                                base + ".search.candidate-pool-multiplier",
                                "origin-rtp.search.candidate-pool-multiplier",
                                8
                        )
                )
        );

        if (wideBorder) {
            candidatePoolMultiplier = Math.max(
                    candidatePoolMultiplier,
                    12
            );
        }

        int recentHistory = Math.max(
                0,
                Math.min(
                        64,
                        integer(
                                core,
                                base + ".search.recent-destination-history",
                                "origin-rtp.search.recent-destination-history",
                                defaultRecentHistory(destination)
                        )
                )
        );

        if (wideBorder) {
            recentHistory = Math.max(
                    recentHistory,
                    destination.equals("overworld")
                            ? 24
                            : 32
            );
        }

        int recentDistance = Math.max(
                0,
                integer(
                        core,
                        base + ".search.minimum-recent-destination-distance",
                        "origin-rtp.search.minimum-recent-destination-distance",
                        defaultRecentDistance(destination)
                )
        );

        if (wideBorder) {
            recentDistance = Math.max(
                    recentDistance,
                    destination.equals("overworld")
                            ? 1500
                            : 4096
            );
        }

        return new OriginRtpSearchSettings(
                destination,
                core.getConfig().getString(
                        base + ".display-name",
                        defaultDisplayName(destination)
                ),
                worldName,
                useWorldBorder,
                worldBorderPadding,
                integer(
                        core,
                        base + ".search.center-x",
                        "origin-rtp.search.center-x",
                        0
                ),
                integer(
                        core,
                        base + ".search.center-z",
                        "origin-rtp.search.center-z",
                        0
                ),
                Math.max(
                        defaultFallbackMaximumRadius(destination),
                        Math.max(
                                1,
                                integer(
                                        core,
                                        base + ".search.fallback-maximum-radius",
                                        "origin-rtp.search.fallback-maximum-radius",
                                        5000
                                )
                        )
                ),
                minimumDistance,
                maximumDistance,
                minimumY,
                maximumY,
                maximumAttempts,
                Math.max(
                        1,
                        Math.min(
                                8,
                                integer(
                                        core,
                                        base + ".search.candidates-per-batch",
                                        "origin-rtp.search.candidates-per-batch",
                                        4
                                )
                        )
                ),
                core.getConfig().getBoolean(
                        base + ".search.surface-only",
                        !destination.equals("nether")
                ),
                safePlatformRadius,
                Math.max(
                        0,
                        Math.min(
                                4,
                                integer(
                                        core,
                                        base + ".search.maximum-ground-height-difference",
                                        "origin-rtp.search.maximum-ground-height-difference",
                                        2
                                )
                        )
                ),
                hazardCheckRadius,
                preferUnexplored,
                candidatePoolMultiplier,
                recentHistory,
                recentDistance,
                destinationBoolean(
                        core,
                        base + ".search.randomized-vertical-search",
                        destination.equals("nether")
                ),
                Set.copyOf(
                        unsafeBlocks(core, base)
                )
        );
    }

    public int clampedMinimumY(World world) {
        return Math.max(
                world.getMinHeight(),
                minimumY
        );
    }

    public int clampedMaximumY(World world) {
        return Math.min(
                world.getMaxHeight() - 3,
                maximumY
        );
    }

    public static String canonicalDestination(
            String input
    ) {
        if (input == null || input.isBlank()) {
            return "overworld";
        }

        return switch (
                input.trim().toLowerCase(Locale.ROOT)
        ) {
            case "origin", "origins", "world",
                 "normal", "overworld" -> "overworld";
            case "the_nether", "overworld_nether",
                 "nether" -> "nether";
            case "the_end", "overworld_the_end",
                 "end" -> "end";
            default -> input.trim()
                    .toLowerCase(Locale.ROOT);
        };
    }

    public static String canonicalWorld(String input) {
        if (input == null || input.isBlank()) {
            return "overworld";
        }

        return switch (
                input.trim().toLowerCase(Locale.ROOT)
        ) {
            case "origins" -> "overworld";
            case "origins_nether" ->
                    "overworld_nether";
            case "origins_the_end" ->
                    "overworld_the_end";
            default -> input.trim();
        };
    }

    private static Set<Material> unsafeBlocks(
            Core core,
            String base
    ) {
        Set<Material> materials = new HashSet<>();
        java.util.List<String> configured =
                core.getConfig().getStringList(
                        base + ".unsafe-blocks"
                );

        if (configured.isEmpty()) {
            configured = core.getConfig().getStringList(
                    "origin-rtp.unsafe-blocks"
            );
        }

        for (String raw : configured) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            Material material = Material.matchMaterial(
                    raw.trim()
            );

            if (material == null) {
                core.getLogger().warning(
                        "Invalid RTP unsafe block: " + raw
                );
                continue;
            }

            materials.add(material);
        }

        materials.add(Material.WATER);
        materials.add(Material.LAVA);
        materials.add(Material.FIRE);
        materials.add(Material.SOUL_FIRE);
        materials.add(Material.CACTUS);
        materials.add(Material.MAGMA_BLOCK);
        materials.add(Material.POWDER_SNOW);
        materials.add(Material.BEDROCK);
        materials.add(Material.SWEET_BERRY_BUSH);
        materials.add(Material.WITHER_ROSE);
        materials.add(Material.CAMPFIRE);
        materials.add(Material.SOUL_CAMPFIRE);
        materials.add(Material.POINTED_DRIPSTONE);
        materials.add(Material.CHORUS_PLANT);
        materials.add(Material.CHORUS_FLOWER);
        materials.add(Material.END_GATEWAY);
        materials.add(Material.END_PORTAL);
        materials.add(Material.END_PORTAL_FRAME);
        materials.add(Material.NETHER_PORTAL);
        materials.add(Material.RESPAWN_ANCHOR);
        materials.add(Material.TWISTING_VINES);
        materials.add(Material.TWISTING_VINES_PLANT);
        materials.add(Material.WEEPING_VINES);
        materials.add(Material.WEEPING_VINES_PLANT);

        return materials;
    }

    private static boolean destinationBoolean(
            Core core,
            String path,
            boolean defaultValue
    ) {
        if (core.getConfig().contains(path)) {
            return core.getConfig().getBoolean(
                    path,
                    defaultValue
            );
        }

        return defaultValue;
    }

    private static int integer(
            Core core,
            String primary,
            String fallback,
            int defaultValue
    ) {
        if (core.getConfig().contains(primary)) {
            return core.getConfig().getInt(
                    primary,
                    defaultValue
            );
        }

        return core.getConfig().getInt(
                fallback,
                defaultValue
        );
    }

    private static String defaultDisplayName(
            String destination
    ) {
        return switch (destination) {
            case "nether" -> "Nether";
            case "end" -> "The End";
            default -> "Overworld";
        };
    }

    private static String defaultWorld(
            String destination
    ) {
        return switch (destination) {
            case "nether" -> "overworld_nether";
            case "end" -> "overworld_the_end";
            default -> "overworld";
        };
    }

    private static int defaultMinimumY(
            String destination
    ) {
        return switch (destination) {
            case "nether" -> 32;
            case "end" -> 48;
            default -> 60;
        };
    }

    private static int defaultMaximumY(
            String destination
    ) {
        return switch (destination) {
            case "nether" -> 118;
            case "end" -> 255;
            default -> 319;
        };
    }

    private static int defaultExplorationDistance(
            String destination
    ) {
        return switch (destination) {
            case "nether", "end" -> 5000;
            default -> 1000;
        };
    }

    private static int defaultFallbackMaximumRadius(
            String destination
    ) {
        return switch (destination) {
            case "nether", "end" -> 29_000_000;
            default -> 1;
        };
    }

    private static boolean defaultPreferUnexplored(
            String destination
    ) {
        return destination.equals("nether")
                || destination.equals("end");
    }

    private static int defaultRecentHistory(
            String destination
    ) {
        return destination.equals("nether")
                || destination.equals("end")
                ? 16
                : 0;
    }

    private static int defaultRecentDistance(
            String destination
    ) {
        return destination.equals("nether")
                || destination.equals("end")
                ? 4096
                : 0;
    }
}
