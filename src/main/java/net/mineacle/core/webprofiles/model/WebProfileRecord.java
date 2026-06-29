package net.mineacle.core.webprofiles.model;

import java.util.UUID;

public record WebProfileRecord(
        UUID uuid,
        String username,
        String displayName,
        String rankName,
        int rankWeight,
        long balanceCents,
        String balanceFormatted,
        long playtimeSeconds,
        String playtimeFormatted,
        long kills,
        long deaths,
        double kdRatio,
        int moneyRank,
        int killsRank,
        int playtimeRank,
        long lastSeen,
        boolean online,
        long updatedAt
) {
}
