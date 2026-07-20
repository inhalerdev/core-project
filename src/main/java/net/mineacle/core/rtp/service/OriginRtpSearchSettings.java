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
        int centerX,
        int centerZ,
        int minRadius,
        int maxRadius,
        String shape,
        int minY,
        int maxY,
        int maxAttempts,
        boolean surfaceOnly,
        int nearbySafetyRadius,
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
        int minimumRadius = Math.max(
                0,
                integer(
                        core,
                        base + ".search.min-radius",
                        "origin-rtp.search.min-radius",
                        250
                )
        );
        int maximumRadius = Math.max(
                minimumRadius + 1,
                integer(
                        core,
                        base + ".search.max-radius",
                        "origin-rtp.search.max-radius",
                        5000
                )
        );
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

        Set<Material> unsafe = unsafeBlocks(
                core,
                base
        );

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
                minimumRadius,
                maximumRadius,
                core.getConfig().getString(
                        base + ".search.shape",
                        core.getConfig().getString(
                                "origin-rtp.search.shape",
                                "circle"
                        )
                ),
                minimumY,
                maximumY,
                Math.max(
                        1,
                        integer(
                                core,
                                base + ".search.max-attempts",
                                "origin-rtp.search.max-attempts",
                                128
                        )
                ),
                core.getConfig().getBoolean(
                        base + ".search.surface-only",
                        !destination.equals("nether")
                ),
                Math.max(
                        0,
                        Math.min(
                                4,
                                integer(
                                        core,
                                        base + ".search.nearby-safety-radius",
                                        "origin-rtp.search.nearby-safety-radius",
                                        1
                                )
                        )
                ),
                Set.copyOf(unsafe)
        );
    }

    public int clampedMinimumY(World world) {
        return Math.max(
                world.getMinHeight(),
                minY
        );
    }

    public int clampedMaximumY(World world) {
        return Math.min(
                world.getMaxHeight() - 3,
                maxY
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
