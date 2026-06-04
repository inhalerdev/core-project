package net.mineacle.core.duels.model;

import org.bukkit.Location;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class DuelSession {

    private final UUID id;
    private final Set<UUID> players;
    private final Set<UUID> alive;
    private final Location arenaLocation;
    private final long startedAt;

    public DuelSession(UUID id, Set<UUID> players, Location arenaLocation) {
        this.id = id;
        this.players = new HashSet<>(players);
        this.alive = new HashSet<>(players);
        this.arenaLocation = arenaLocation;
        this.startedAt = System.currentTimeMillis();
    }

    public UUID id() {
        return id;
    }

    public Set<UUID> players() {
        return players;
    }

    public Set<UUID> alive() {
        return alive;
    }

    public Location arenaLocation() {
        return arenaLocation;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean contains(UUID playerId) {
        return players.contains(playerId);
    }

    public boolean alive(UUID playerId) {
        return alive.contains(playerId);
    }

    public void eliminate(UUID playerId) {
        alive.remove(playerId);
    }

    public boolean finished() {
        return alive.size() <= 1;
    }
}
