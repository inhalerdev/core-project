package net.mineacle.core.bounty;

import java.util.UUID;

public record BountyRecord(
        UUID targetId,
        String targetName,
        long amount,
        long updatedAt
) {
}