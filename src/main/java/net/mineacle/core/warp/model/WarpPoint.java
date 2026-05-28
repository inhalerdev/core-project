package net.mineacle.core.warps.model;

import org.bukkit.Material;

public record WarpPoint(
        String key,
        String displayName,
        Material material,
        int slot,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
}
