package net.mineacle.core.duels.model;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class DuelSession {

    private final UUID id;
    private final Set<UUID> players;
    private final long createdAt;
    private final long fightStartsAt;
    private final long expiresAt;
    private boolean released;

    public DuelSession(UUID id, Set<UUID> players, long createdAt, long fightStartsAt, long expiresAt) {
        this.id = id;
        this.players = new HashSet<>(players);
        this.createdAt = createdAt;
        this.fightStartsAt = fightStartsAt;
        this.expiresAt = expiresAt;
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

    public long fightStartsAt() {
        return fightStartsAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public boolean released() {
        return released;
    }

    public void release() {
        this.released = true;
    }

    public boolean contains(UUID playerId) {
        return players.contains(playerId);
    }

    public boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean frozen() {
        return !released && System.currentTimeMillis() < fightStartsAt;
    }
}
