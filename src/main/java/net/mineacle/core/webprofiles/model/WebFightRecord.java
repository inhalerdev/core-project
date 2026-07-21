package net.mineacle.core.webprofiles.model;

import java.util.UUID;

public record WebFightRecord(
        UUID fightId,
        UUID winnerUuid,
        String winnerUsername,
        String winnerDisplayName,
        UUID loserUuid,
        String loserUsername,
        String loserDisplayName,
        String worldKey,
        String worldName,
        double winnerHearts,
        double loserHearts,
        long startedAt,
        long endedAt,
        long durationSeconds
) {
}
