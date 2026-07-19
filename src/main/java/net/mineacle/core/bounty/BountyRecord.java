package net.mineacle.core.bounty;

import java.util.Objects;
import java.util.UUID;

public record BountyRecord(
        UUID targetId,
        String targetUsername,
        long amountCents,
        long lastUpdated
) {

    public BountyRecord {
        Objects.requireNonNull(targetId, "targetId");
        targetUsername = targetUsername == null
                ? targetId.toString()
                : targetUsername;
        amountCents = Math.max(0L, amountCents);
        lastUpdated = Math.max(0L, lastUpdated);
    }

    /**
     * Compatibility accessor retained for older MineacleCore integrations.
     */
    public String targetName() {
        return targetUsername;
    }
}
