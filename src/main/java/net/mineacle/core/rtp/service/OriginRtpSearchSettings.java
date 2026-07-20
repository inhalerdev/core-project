package net.mineacle.core.rtp.service;

import net.mineacle.core.Core;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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
        Set<Material> unsafeBlocks
) {

    public static OriginRtpSearchSettings fromConfig(
            Core core,
            String rawDestination
    ) {
        String destination = canonicalDestination(
                rawDestination
        );
        String base = "origin-rtp.destinations."
                + destination;
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

        int minimumDistance = Math.max(
                0,
                integer(
                        core,
                        base
                                + ".search.minimum-distance-from-world-spawn",
                        "origin-rtp.search.minimum-distance-from-world-spawn",
                        1000
                )
        );
        int maximumDistance = Math.max(
                0,
                integer(
                        core,
                        base
                                + ".search.maximum-distance-from-world-spawn",
                        "origin-rtp.search.maximum-distance-from-world-spawn",
                        0
                )
        );

        if (maximumDistance > 0
                && maximumDistance <= minimumDistance) {
            maximumDistance = 0;
        }

        return new OriginRtpSearchSettings(
                destination,
                core.getConfig().getString(
                        base + ".display-name",
                        defaultDisplayName(destination)
                ),
                canonicalWorld(
                        core.getConfig().getString(
                                base + ".world",
                                defaultWorld(destination)
                        )
                ),
                core.getConfig().getBoolean(
                        base + ".search.use-world-border",
                        core.getConfig().getBoolean(
                                "origin-rtp.search.use-world-border",
                                true
                        )
                ),
                Math.max(
                        0,
                        integer(
                                core,
                                base
                                        + ".search.world-border-padding",
                                "origin-rtp.search.world-border-padding",
                                32
                        )
                ),
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
                        1,
                        integer(
                                core,
                                base
                                        + ".search.fallback-maximum-radius",
                                "origin-rtp.search.fallback-maximum-radius",
                                5000
                        )
                ),
                minimumDistance,
                maximumDistance,
                minimumY,
                maximumY,
                Math.max(
                        1,
                        integer(
                                core,
                                base + ".search.max-attempts",
                                "origin-rtp.search.max-attempts",
                                160
                        )
                ),
                Math.max(
                        1,
                        Math.min(
                                8,
                                integer(
                                        core,
                                        base
                                                + ".search.candidates-per-batch",
                                        "origin-rtp.search.candidates-per-batch",
                                        4
                                )
                        )
                ),
                core.getConfig().getBoolean(
                        base + ".search.surface-only",
                        !destination.equals("nether")
                ),
                Math.max(
                        0,
                        Math.min(
                                2,
                                integer(
                                        core,
                                        base
                                                + ".search.safe-platform-radius",
                                        "origin-rtp.search.safe-platform-radius",
                                        1
                                )
                        )
                ),
                Math.max(
                        0,
                        Math.min(
                                4,
                                integer(
                                        core,
                                        base
                                                + ".search.maximum-ground-height-difference",
                                        "origin-rtp.search.maximum-ground-height-difference",
                                        2
                                )
                        )
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

        if (materials.isEmpty()) {
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
        }

        return materials;
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
}
