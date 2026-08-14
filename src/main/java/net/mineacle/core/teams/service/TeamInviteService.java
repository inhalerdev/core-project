package net.mineacle.core.teams.service;

import net.mineacle.core.Core;
import net.mineacle.core.teams.model.TeamInviteRecord;
import net.mineacle.core.teams.model.TeamRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TeamInviteService {

    private static final long GLOBAL_PURGE_INTERVAL_MILLIS =
            5_000L;

    private final Core core;
    private final TeamService teamService;
    private final Map<UUID, TeamInviteRecord> invites =
            new HashMap<>();

    private long lastGlobalPurgeMillis;

    public TeamInviteService(
            Core core,
            TeamService teamService
    ) {
        this.core = core;
        this.teamService = teamService;
    }


    public int timeoutSeconds() {
        if (core == null) {
            return 120;
        }

        return Math.max(
                10,
                core.getConfig().getInt(
                        "teams.invites.timeout-seconds",
                        120
                )
        );
    }

    public boolean createInvite(
            String teamId,
            UUID inviterId,
            UUID targetId
    ) {
        if (teamId == null
                || inviterId == null
                || targetId == null
                || inviterId.equals(targetId)) {
            return false;
        }

        purgeExpiredIfDue();

        TeamRecord team =
                teamService.getTeamById(
                        teamId
                );

        if (team == null
                || teamService.hasTeam(
                targetId
        )
                || teamService.isBanned(
                teamId,
                targetId
        )
                || teamService.memberCount(
                teamId
        ) >= teamService.maxMembers()) {
            return false;
        }

        invites.put(
                targetId,
                new TeamInviteRecord(
                        teamId,
                        inviterId,
                        targetId,
                        System.currentTimeMillis()
                )
        );
        return true;
    }

    public boolean hasInvite(
            UUID playerId
    ) {
        purgeExpired(playerId);
        return invites.containsKey(
                playerId
        );
    }

    public TeamInviteRecord getInvite(
            UUID playerId
    ) {
        purgeExpired(playerId);
        return invites.get(playerId);
    }

    public long remainingSeconds(
            UUID playerId
    ) {
        TeamInviteRecord invite =
                getInvite(playerId);

        if (invite == null) {
            return 0L;
        }

        long expiresAt =
                invite.createdAt()
                        + timeoutSeconds()
                        * 1000L;
        long remaining =
                (expiresAt
                        - System.currentTimeMillis()
                ) / 1000L;

        return Math.max(
                0L,
                remaining
        );
    }

    public boolean acceptInvite(
            UUID playerId
    ) {
        purgeExpired(playerId);

        TeamInviteRecord invite =
                invites.remove(playerId);

        if (invite == null
                || teamService.isBanned(
                invite.teamId(),
                playerId
        )) {
            return false;
        }

        return teamService.addMember(
                invite.teamId(),
                playerId
        );
    }

    public boolean denyInvite(
            UUID playerId
    ) {
        purgeExpired(playerId);
        return invites.remove(playerId)
                != null;
    }


    public void clearAll() {
        invites.clear();
    }

    public void purgeExpired() {
        invites.entrySet()
                .removeIf(
                        entry -> isExpired(
                                entry.getValue()
                        )
                );

        lastGlobalPurgeMillis =
                System.currentTimeMillis();
    }

    private void purgeExpiredIfDue() {
        long now =
                System.currentTimeMillis();

        if (now - lastGlobalPurgeMillis
                < GLOBAL_PURGE_INTERVAL_MILLIS) {
            return;
        }

        purgeExpired();
    }

    private void purgeExpired(
            UUID playerId
    ) {
        if (playerId == null) {
            return;
        }

        TeamInviteRecord invite =
                invites.get(playerId);

        if (invite != null
                && isExpired(invite)) {
            invites.remove(playerId);
        }
    }

    private boolean isExpired(
            TeamInviteRecord invite
    ) {
        long age =
                System.currentTimeMillis()
                        - invite.createdAt();

        return age
                >= timeoutSeconds()
                * 1000L;
    }
}
