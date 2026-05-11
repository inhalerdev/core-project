package net.mineacle.core.rtp.service;

import net.mineacle.core.Core;
import org.bukkit.Material;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public record OriginRtpSearchSettings(
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

    public static OriginRtpSearchSettings fromConfig(Core core) {
        Set<Material> unsafeBlocks = new HashSet<>();

        for (String rawMaterial : core.getConfig().getStringList("origin-rtp.unsafe-blocks")) {
            if (rawMaterial == null || rawMaterial.isBlank()) {
                continue;
            }

            try {
                unsafeBlocks.add(Material.valueOf(rawMaterial.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                core.getLogger().warning("Invalid origin-rtp unsafe block: " + rawMaterial);
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

        int minRadius = Math.max(0, core.getConfig().getInt("origin-rtp.search.min-radius", 250));
        int maxRadius = Math.max(minRadius + 1, core.getConfig().getInt("origin-rtp.search.max-radius", 5000));

        int minY = core.getConfig().getInt("origin-rtp.search.min-y", 64);
        int maxY = core.getConfig().getInt("origin-rtp.search.max-y", 320);

        if (maxY <= minY) {
            maxY = minY + 1;
        }

        return new OriginRtpSearchSettings(
                core.getConfig().getString("origin-rtp.world", "origins"),
                core.getConfig().getBoolean("origin-rtp.search.use-world-border", true),
                core.getConfig().getInt("origin-rtp.search.center-x", 0),
                core.getConfig().getInt("origin-rtp.search.center-z", 0),
                minRadius,
                maxRadius,
                core.getConfig().getString("origin-rtp.search.shape", "square"),
                minY,
                maxY,
                Math.max(1, core.getConfig().getInt("origin-rtp.search.max-attempts", 64)),
                Math.max(0, core.getConfig().getInt("origin-rtp.search.preload-radius", 2)),
                unsafeBlocks
        );
    }
}