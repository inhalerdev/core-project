package net.mineacle.core.duels.model;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class DuelSession {

    private final UUID id;
    private final Set<UUID> players;
    private final long createdAt;

    public DuelSession(UUID id, Set<UUID> players, long createdAt) {
        this.id = id;
        this.players = new HashSet<>(players);
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public Set<UUID> players() {
        return players;
    }

    public long createdAt() {
        return createdAt;
    }

    public boolean contains(UUID playerId) {
        return players.contains(playerId);
    }
}
