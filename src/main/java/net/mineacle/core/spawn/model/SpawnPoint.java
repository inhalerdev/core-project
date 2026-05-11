package net.mineacle.core.spawn.model;

public record SpawnPoint(
        String id,
        String displayName,
        String worldName,
        int slot,
        boolean enabled
) {
}