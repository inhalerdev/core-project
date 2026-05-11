package net.mineacle.core.teams.model;

import java.util.UUID;

public final class TeamBanRecord {

    private final String teamId;
    private final UUID playerId;
    private final UUID bannedBy;
    private final long createdAt;
    private final long expiresAt;

    public TeamBanRecord(String teamId, UUID playerId, UUID bannedBy, long createdAt, long expiresAt) {
        this.teamId = teamId;
        this.playerId = playerId;
        this.bannedBy = bannedBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String teamId() {
        return teamId;
    }

    public UUID playerId() {
        return playerId;
    }

    public UUID bannedBy() {
        return bannedBy;
    }

    public long createdAt() {
        return createdAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public boolean expired() {
        return System.currentTimeMillis() >= expiresAt;
    }
}