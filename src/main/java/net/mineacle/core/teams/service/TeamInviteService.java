package net.mineacle.core.teams.service;

import net.mineacle.core.Core;
import net.mineacle.core.teams.model.TeamInviteRecord;
import net.mineacle.core.teams.model.TeamMemberRecord;
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
                teamService.getTeamById(teamId);
        TeamMemberRecord inviter =
                teamService.getMember(inviterId);

        if (team == null
                || inviter == null
                || !teamId.equals(inviter.teamId())
                || !teamService.canInvite(inviterId)
                || teamService.hasTeam(targetId)
                || teamService.isBanned(teamId, targetId)
                || teamService.memberCount(teamId)
                >= teamService.maxMembers()) {
            return false;
        }

        TeamInviteRecord existing =
                getInvite(targetId);

        if (existing != null
                && !existing.teamId().equals(teamId)) {
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
        return getInvite(playerId) != null;
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
                        * 1_000L;
        long remaining =
                (expiresAt
                        - System.currentTimeMillis()
                ) / 1_000L;

        return Math.max(
                0L,
                remaining
        );
    }

    public boolean acceptInvite(
            UUID playerId
    ) {
        return acceptInvite(
                playerId,
                null,
                null,
                0L
        );
    }

    public boolean acceptInvite(
            UUID playerId,
            String expectedTeamId,
            UUID expectedInviterId,
            long expectedCreatedAt
    ) {
        TeamInviteRecord invite =
                validatedInvite(
                        playerId,
                        expectedTeamId,
                        expectedInviterId,
                        expectedCreatedAt
                );

        if (invite == null) {
            return false;
        }

        boolean joined = teamService.addMember(
                invite.teamId(),
                playerId
        );

        if (!joined) {
            return false;
        }

        invites.remove(
                playerId,
                invite
        );
        return true;
    }

    public boolean denyInvite(
            UUID playerId
    ) {
        TeamInviteRecord invite =
                getInvite(playerId);

        return invite != null
                && invites.remove(
                playerId,
                invite
        );
    }

    public boolean denyInvite(
            UUID playerId,
            String expectedTeamId,
            UUID expectedInviterId,
            long expectedCreatedAt
    ) {
        TeamInviteRecord invite =
                getInvite(playerId);

        return matches(
                invite,
                expectedTeamId,
                expectedInviterId,
                expectedCreatedAt
        ) && invites.remove(
                playerId,
                invite
        );
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

    private TeamInviteRecord validatedInvite(
            UUID playerId,
            String expectedTeamId,
            UUID expectedInviterId,
            long expectedCreatedAt
    ) {
        TeamInviteRecord invite =
                getInvite(playerId);

        if (invite == null
                || !matches(
                invite,
                expectedTeamId,
                expectedInviterId,
                expectedCreatedAt
        )) {
            return null;
        }

        TeamRecord team =
                teamService.getTeamById(
                        invite.teamId()
                );
        TeamMemberRecord inviter =
                teamService.getMember(
                        invite.inviterId()
                );

        if (team == null
                || inviter == null
                || !invite.teamId().equals(
                inviter.teamId()
        )
                || !teamService.canInvite(
                invite.inviterId()
        )
                || teamService.hasTeam(playerId)
                || teamService.isBanned(
                invite.teamId(),
                playerId
        )
                || teamService.memberCount(
                invite.teamId()
        ) >= teamService.maxMembers()) {
            return null;
        }

        return invite;
    }

    private boolean matches(
            TeamInviteRecord invite,
            String expectedTeamId,
            UUID expectedInviterId,
            long expectedCreatedAt
    ) {
        if (invite == null) {
            return false;
        }

        if (expectedTeamId == null) {
            return true;
        }

        return expectedTeamId.equals(
                invite.teamId()
        )
                && expectedInviterId != null
                && expectedInviterId.equals(
                invite.inviterId()
        )
                && expectedCreatedAt
                == invite.createdAt();
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
            invites.remove(
                    playerId,
                    invite
            );
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
                * 1_000L;
    }
}
