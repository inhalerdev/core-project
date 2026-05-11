package net.mineacle.core.teams.model;

import java.util.UUID;

public record TeamRecord(
        String teamId,
        String name,
        UUID founder,
        boolean friendlyFire
) {
}