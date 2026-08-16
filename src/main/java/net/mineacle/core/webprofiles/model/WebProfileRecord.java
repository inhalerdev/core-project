package net.mineacle.core.webprofiles.model;

import net.mineacle.core.common.player.VanishRegistry;
import net.mineacle.core.common.player.VanishRegistry.WebPrivacySnapshot;

import java.util.UUID;

public record WebProfileRecord(
        UUID uuid,
        String username,
        String displayName,
        String rankKey,
        String rankName,
        String rankPrefix,
        String rankColor,
        int rankWeight,
        String worldKey,
        String worldName,
        String worldGroup,
        String teamId,
        String teamName,
        String teamRole,
        long teamJoinedAt,
        long balanceCents,
        String balanceFormatted,
        long playtimeSeconds,
        String playtimeFormatted,
        long kills,
        long deaths,
        double kdRatio,
        int moneyRank,
        int killsRank,
        int playtimeRank,
        long firstJoinedAt,
        long lastSeen,
        boolean online,
        long updatedAt
) {

    public WebProfileRecord {
        if (VanishRegistry.isVanished(uuid)) {
            /*
             * A vanished staff member is publicly offline.
             *
             * World fields are intentionally blank. WebProfileRepository
             * already preserves the last non-blank world on updates, so
             * hidden movement never replaces the last public world.
             */
            online = false;
            worldKey = "";
            worldName = "";
            worldGroup = "";

            WebPrivacySnapshot snapshot =
                    VanishRegistry
                            .webPrivacySnapshot(uuid);

            if (snapshot == null) {
                /*
                 * Fail closed if registry state is ever incomplete.
                 * Do not publish current-session activity for a vanished UUID.
                 */
                playtimeSeconds = 0L;
                playtimeFormatted = "0m";
                kills = 0L;
                deaths = 0L;
                kdRatio = 0.0D;
                lastSeen = 0L;
            } else {
                playtimeSeconds =
                        snapshot.playtimeSeconds();
                playtimeFormatted =
                        snapshot.playtimeFormatted();
                kills = snapshot.kills();
                deaths = snapshot.deaths();
                kdRatio = snapshot.kdRatio();
                lastSeen = snapshot.lastSeen();
            }
        }
    }
}
