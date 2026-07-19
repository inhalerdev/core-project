package net.mineacle.core.webprofiles.model;

import java.util.UUID;

public record WebProfileRecord(
        UUID uuid,
        String username,
        String displayName,
        String rankKey,
        String rankName,
        String rankPrefix,
        String rankColor,
        int rankWeight,
        String worldKey,
        String worldName,
        String worldGroup,
        String teamId,
        String teamName,
        String teamRole,
        long teamJoinedAt,
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
        long firstJoinedAt,
        long lastSeen,
        boolean online,
        long updatedAt
) {
}
