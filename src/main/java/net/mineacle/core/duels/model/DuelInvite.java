package net.mineacle.core.duels.model;

import java.util.UUID;

public record DuelInvite(UUID challengerId, UUID targetId, long expiresAt) {

    public boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
