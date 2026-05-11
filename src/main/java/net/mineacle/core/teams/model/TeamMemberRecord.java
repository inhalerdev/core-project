package net.mineacle.core.teams.model;

import java.util.UUID;

public record TeamMemberRecord(
        String teamId,
        UUID playerId,
        TeamRole role,
        long joinedAt
) {
}