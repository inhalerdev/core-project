package net.mineacle.core.common.player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishRegistry {

    private static final Set<UUID> VANISHED =
            ConcurrentHashMap.newKeySet();
    private static final Map<UUID, WebPrivacySnapshot>
            WEB_PRIVACY =
            new ConcurrentHashMap<>();

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
            WEB_PRIVACY.remove(playerId);
        }
    }

    public static WebPrivacySnapshot webPrivacySnapshot(
            UUID playerId
    ) {
        return playerId == null
                ? null
                : WEB_PRIVACY.get(playerId);
    }

    public static void setWebPrivacySnapshot(
            UUID playerId,
            WebPrivacySnapshot snapshot
    ) {
        if (playerId == null) {
            return;
        }

        if (snapshot == null) {
            WEB_PRIVACY.remove(playerId);
        } else {
            WEB_PRIVACY.put(playerId, snapshot);
        }
    }

    public static void clear() {
        VANISHED.clear();
        WEB_PRIVACY.clear();
    }

    public record WebPrivacySnapshot(
            long playtimeSeconds,
            String playtimeFormatted,
            long kills,
            long deaths,
            double kdRatio,
            long lastSeen
    ) {
        public WebPrivacySnapshot {
            playtimeSeconds = Math.max(
                    0L,
                    playtimeSeconds
            );
            playtimeFormatted =
                    playtimeFormatted == null
                            || playtimeFormatted.isBlank()
                            ? "0m"
                            : playtimeFormatted;
            kills = Math.max(0L, kills);
            deaths = Math.max(0L, deaths);
            kdRatio = Double.isFinite(kdRatio)
                    && kdRatio >= 0.0D
                    ? kdRatio
                    : 0.0D;
            lastSeen = Math.max(0L, lastSeen);
        }
    }
}
