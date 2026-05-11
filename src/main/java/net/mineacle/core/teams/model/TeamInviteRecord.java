package net.mineacle.core.teams.model;

import java.util.UUID;

public record TeamInviteRecord(
        String teamId,
        UUID inviterId,
        UUID targetId,
        long createdAt
) {
}