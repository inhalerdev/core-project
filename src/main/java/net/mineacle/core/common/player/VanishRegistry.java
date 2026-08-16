package net.mineacle.core.common.player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishRegistry {

    private static final Set<UUID> VANISHED =
            ConcurrentHashMap.newKeySet();

    private VanishRegistry() {
    }

    public static boolean isVanished(UUID playerId) {
        return playerId != null
                && VANISHED.contains(playerId);
    }

    public static void setVanished(
            UUID playerId,
            boolean vanished
    ) {
        if (playerId == null) {
            return;
        }

        if (vanished) {
            VANISHED.add(playerId);
        } else {
            VANISHED.remove(playerId);
        }
    }

    public static void clear() {
        VANISHED.clear();
    }
}
