package net.mineacle.core.warps.model;

import org.bukkit.Material;

import java.util.List;

public record WarpPoint(
        String id,
        String displayName,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        int slot,
        Material material,
        boolean enabled,
        List<String> lore
) {
}
