package net.mineacle.core.teams.service;

import net.mineacle.core.Core;
import net.mineacle.core.teams.model.TeamBanRecord;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.model.TeamRole;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Main-thread team registry.
 *
 * <p>Reads are served from indexes in memory. Mutations update only the
 * affected YAML paths and Core's shared DebouncedYamlPersistence owns disk
 * flushing. The public API intentionally keeps compatibility methods used by
 * Homes, Chat and placeholders while centralizing role/capability decisions
 * here.</p>
 */
public final class TeamService {

    private final Core core;

    private final Map<String, TeamRecord> teams =
            new HashMap<>();
    private final Map<UUID, TeamMemberRecord> members =
            new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> membersByTeam =
            new HashMap<>();
    private final Map<String, String> nameIndex =
            new HashMap<>();
    private final Map<String, Map<UUID, TeamBanRecord>> bans =
            new HashMap<>();
    private final Set<UUID> teamChatEnabled =
            ConcurrentHashMap.newKeySet();

    public TeamService(Core core) {
        this.core = core;
        load();
    }

    public int maxMembers() {
        return Math.clamp(
                core.getConfig().getInt(
                        "teams.max-members",
                        45
                ),
                1,
                45
        );
    }

    public int banDays() {
        return Math.max(
                1,
                core.getConfig().getInt(
                        "teams.ban-days",
                        7
                )
        );
    }

    public boolean hasTeam(UUID playerId) {
        return playerId != null
                && members.containsKey(playerId);
    }

    public TeamRecord getTeamByPlayer(UUID playerId) {
        TeamMemberRecord member =
                members.get(playerId);

        return member == null
                ? null
                : teams.get(member.teamId());
    }

    public TeamRecord getTeamById(String teamId) {
        return teamId == null
                ? null
                : teams.get(teamId);
    }

    public TeamRecord getTeamByName(String name) {
        if (name == null) {
            return null;
        }

        String id = nameIndex.get(
                name.toLowerCase(Locale.ROOT)
        );

        return id == null
                ? null
                : teams.get(id);
    }

    public TeamMemberRecord getMember(UUID playerId) {
        return playerId == null
                ? null
                : members.get(playerId);
    }

    public TeamRole role(UUID playerId) {
        TeamMemberRecord member =
                getMember(playerId);

        return member == null
                ? null
                : member.role();
    }

    public List<UUID> getTeamMembers(String teamId) {
        if (teamId == null) {
            return List.of();
        }

        Set<UUID> indexed =
                membersByTeam.get(teamId);

        if (indexed == null
                || indexed.isEmpty()) {
            return List.of();
        }

        List<TeamMemberRecord> records =
                new ArrayList<>(
                        indexed.size()
                );

        for (UUID playerId : indexed) {
            TeamMemberRecord member =
                    members.get(playerId);

            if (member != null
                    && teamId.equals(
                    member.teamId()
            )) {
                records.add(member);
            }
        }

        records.sort(
                Comparator
                        .comparingInt(
                                (TeamMemberRecord member) ->
                                        member.role()
                                                .priority()
                        )
                        .thenComparingLong(
                                TeamMemberRecord::joinedAt
                        )
        );

        List<UUID> ids =
                new ArrayList<>(
                        records.size()
                );

        for (TeamMemberRecord record : records) {
            ids.add(record.playerId());
        }

        return List.copyOf(ids);
    }

    public int memberCount(String teamId) {
        Set<UUID> indexed =
                membersByTeam.get(teamId);

        return indexed == null
                ? 0
                : indexed.size();
    }

    public boolean isFounder(UUID playerId) {
        TeamRole role = role(playerId);
        return role != null
                && role.isFounder();
    }

    /**
     * Legacy compatibility for the existing Homes integration. Team Home
     * setup/delete authority is Founder-only; all new moderation checks use
     * the explicit capability methods below instead of this ambiguous name.
     */
    public boolean isAdmin(UUID playerId) {
        return isFounder(playerId);
    }

    public boolean canInvite(UUID playerId) {
        TeamRole role = role(playerId);
        return role != null
                && role.canInvite();
    }


    public boolean canManageBans(UUID playerId) {
        TeamRole role = role(playerId);
        return role != null
                && role.canManageBans();
    }

    public boolean canTogglePvp(UUID playerId) {
        TeamRole role = role(playerId);
        return role != null
                && role.canTogglePvp();
    }

    public boolean canManageTeamHome(UUID playerId) {
        TeamRole role = role(playerId);
        return role != null
                && role.canManageTeamHome();
    }

    public boolean isTeamChatEnabled(UUID playerId) {
        return playerId != null
                && teamChatEnabled.contains(
                playerId
        );
    }

    public boolean toggleTeamChat(UUID playerId) {
        boolean enabled =
                !isTeamChatEnabled(playerId);

        setTeamChat(
                playerId,
                enabled
        );
        return enabled;
    }

    public void setTeamChat(
            UUID playerId,
            boolean enabled
    ) {
        if (playerId == null) {
            return;
        }

        if (enabled) {
            teamChatEnabled.add(playerId);
            core.getTeamsConfig().set(
                    "team-chat." + playerId,
                    true
            );
        } else {
            teamChatEnabled.remove(playerId);
            core.getTeamsConfig().set(
                    "team-chat." + playerId,
                    null
            );
        }

        persist();
    }

    public boolean isValidTeamName(String name) {
        if (name == null) {
            return false;
        }

        String cleaned = name.trim();

        return cleaned.length() >= 3
                && cleaned.length() <= 16
                && cleaned.matches(
                "[A-Za-z0-9_]+"
        );
    }

    public boolean createTeam(
            UUID founderId,
            String name
    ) {
        if (founderId == null
                || hasTeam(founderId)
                || !isValidTeamName(name)
                || getTeamByName(name) != null) {
            return false;
        }

        String cleanedName =
                name.trim();
        String teamId =
                UUID.randomUUID()
                        .toString();
        long joinedAt =
                System.currentTimeMillis();

        TeamRecord team =
                new TeamRecord(
                        teamId,
                        cleanedName,
                        founderId,
                        false
                );
        TeamMemberRecord founder =
                new TeamMemberRecord(
                        teamId,
                        founderId,
                        TeamRole.FOUNDER,
                        joinedAt
                );

        teams.put(teamId, team);
        members.put(founderId, founder);
        membersByTeam
                .computeIfAbsent(
                        teamId,
                        ignored ->
                                new LinkedHashSet<>()
                )
                .add(founderId);
        nameIndex.put(
                cleanedName
                        .toLowerCase(
                                Locale.ROOT
                        ),
                teamId
        );

        writeTeam(team);
        writeMember(founder);
        persist();
        return true;
    }

    public boolean addMember(
            String teamId,
            UUID playerId
    ) {
        TeamRecord team =
                teams.get(teamId);

        if (team == null
                || playerId == null
                || hasTeam(playerId)
                || isBanned(teamId, playerId)
                || memberCount(teamId)
                >= maxMembers()) {
            return false;
        }

        TeamMemberRecord member =
                new TeamMemberRecord(
                        teamId,
                        playerId,
                        TeamRole.MEMBER,
                        System.currentTimeMillis()
                );

        members.put(playerId, member);
        membersByTeam
                .computeIfAbsent(
                        teamId,
                        ignored ->
                                new LinkedHashSet<>()
                )
                .add(playerId);

        writeMember(member);
        persist();
        return true;
    }

    public boolean removeMember(UUID playerId) {
        TeamMemberRecord member =
                members.get(playerId);

        if (member == null
                || member.role()
                == TeamRole.FOUNDER) {
            return false;
        }

        removeMemberState(member);
        persist();
        return true;
    }

    public boolean promoteMember(
            UUID actorId,
            UUID targetId
    ) {
        TeamMemberRecord actor =
                members.get(actorId);
        TeamMemberRecord target =
                members.get(targetId);

        if (differentTeam(actor, target)
                || actor.role()
                != TeamRole.FOUNDER
                || actorId.equals(targetId)
                || !target.role()
                .canBePromoted()) {
            return false;
        }

        return setMemberRoleInternal(
                target,
                target.role().promoted()
        );
    }

    public boolean demoteMember(
            UUID actorId,
            UUID targetId
    ) {
        TeamMemberRecord actor =
                members.get(actorId);
        TeamMemberRecord target =
                members.get(targetId);

        if (differentTeam(actor, target)
                || actor.role()
                != TeamRole.FOUNDER
                || actorId.equals(targetId)
                || !target.role()
                .canBeDemoted()) {
            return false;
        }

        return setMemberRoleInternal(
                target,
                target.role().demoted()
        );
    }


    public boolean kickMember(
            UUID actorId,
            UUID targetId
    ) {
        TeamMemberRecord actor =
                members.get(actorId);
        TeamMemberRecord target =
                members.get(targetId);

        if (cannotModerate(
                actorId,
                actor,
                targetId,
                target
        )) {
            return false;
        }

        removeMemberState(target);
        persist();
        return true;
    }

    public boolean banMember(
            UUID actorId,
            UUID targetId
    ) {
        TeamMemberRecord actor =
                members.get(actorId);
        TeamMemberRecord target =
                members.get(targetId);

        if (cannotModerate(
                actorId,
                actor,
                targetId,
                target
        )) {
            return false;
        }

        long createdAt =
                System.currentTimeMillis();
        long expiresAt =
                createdAt
                        + TimeUnit.DAYS
                        .toMillis(
                                banDays()
                        );

        TeamBanRecord record =
                new TeamBanRecord(
                        target.teamId(),
                        targetId,
                        actorId,
                        createdAt,
                        expiresAt
                );

        bans.computeIfAbsent(
                target.teamId(),
                ignored ->
                        new HashMap<>()
        ).put(
                targetId,
                record
        );

        writeBan(record);
        removeMemberState(target);
        persist();
        return true;
    }

    public boolean isBanned(
            String teamId,
            UUID playerId
    ) {
        if (teamId == null
                || playerId == null) {
            return false;
        }

        Map<UUID, TeamBanRecord> teamBans =
                bans.get(teamId);

        if (teamBans == null) {
            return false;
        }

        TeamBanRecord record =
                teamBans.get(playerId);

        if (record == null) {
            return false;
        }

        if (!record.expired()) {
            return true;
        }

        removeExpiredBan(
                teamId,
                playerId,
                teamBans
        );
        persist();
        return false;
    }

    public List<TeamBanRecord> activeBans(
            String teamId
    ) {
        if (teamId == null
                || teamId.isBlank()) {
            return List.of();
        }

        Map<UUID, TeamBanRecord> teamBans =
                bans.get(teamId);

        if (teamBans == null
                || teamBans.isEmpty()) {
            return List.of();
        }

        boolean changed = false;
        List<TeamBanRecord> active =
                new ArrayList<>(
                        teamBans.size()
                );

        for (TeamBanRecord record :
                new ArrayList<>(
                        teamBans.values()
                )) {
            if (record.expired()) {
                removeExpiredBan(
                        teamId,
                        record.playerId(),
                        teamBans
                );
                changed = true;
                continue;
            }

            active.add(record);
        }

        if (changed) {
            persist();
        }

        active.sort(
                Comparator.comparingLong(
                        TeamBanRecord::createdAt
                ).reversed()
        );

        return List.copyOf(active);
    }

    public boolean unbanMember(
            UUID actorId,
            UUID targetId
    ) {
        TeamMemberRecord actor =
                members.get(actorId);

        if (actor == null
                || !actor.role()
                .canManageBans()
                || targetId == null) {
            return false;
        }

        Map<UUID, TeamBanRecord> teamBans =
                bans.get(actor.teamId());

        if (teamBans == null
                || teamBans.remove(targetId)
                == null) {
            return false;
        }

        core.getTeamsConfig().set(
                "team-bans."
                        + actor.teamId()
                        + "."
                        + targetId,
                null
        );

        if (teamBans.isEmpty()) {
            bans.remove(actor.teamId());
        }

        persist();
        return true;
    }

    public boolean transferFounder(
            UUID actorId,
            UUID targetId
    ) {
        TeamMemberRecord actor =
                members.get(actorId);
        TeamMemberRecord target =
                members.get(targetId);

        if (differentTeam(actor, target)
                || actor.role()
                != TeamRole.FOUNDER
                || actorId.equals(targetId)) {
            return false;
        }

        TeamRecord team =
                teams.get(actor.teamId());

        if (team == null) {
            return false;
        }

        TeamRecord updatedTeam =
                new TeamRecord(
                        team.teamId(),
                        team.name(),
                        targetId,
                        team.friendlyFire()
                );
        TeamMemberRecord oldFounder =
                new TeamMemberRecord(
                        actor.teamId(),
                        actorId,
                        TeamRole.MVP,
                        actor.joinedAt()
                );
        TeamMemberRecord newFounder =
                new TeamMemberRecord(
                        target.teamId(),
                        targetId,
                        TeamRole.FOUNDER,
                        target.joinedAt()
                );

        teams.put(
                team.teamId(),
                updatedTeam
        );
        members.put(
                actorId,
                oldFounder
        );
        members.put(
                targetId,
                newFounder
        );

        writeTeam(updatedTeam);
        writeMember(oldFounder);
        writeMember(newFounder);
        persist();
        return true;
    }

    public boolean disbandTeam(UUID actorId) {
        TeamRecord team =
                getTeamByPlayer(actorId);

        if (team == null
                || !team.founder()
                .equals(actorId)) {
            return false;
        }

        teams.remove(team.teamId());
        nameIndex.remove(
                team.name()
                        .toLowerCase(
                                Locale.ROOT
                        )
        );
        bans.remove(team.teamId());

        LinkedHashSet<UUID> indexed =
                membersByTeam.remove(
                        team.teamId()
                );
        List<UUID> toRemove =
                indexed == null
                        ? List.of()
                        : new ArrayList<>(
                        indexed
                );

        for (UUID id : toRemove) {
            members.remove(id);
            teamChatEnabled.remove(id);
            core.getTeamsConfig().set(
                    "team-chat." + id,
                    null
            );
        }

        core.getTeamsConfig().set(
                "teams." + team.teamId(),
                null
        );
        core.getTeamsConfig().set(
                "team-homes."
                        + team.teamId(),
                null
        );
        core.getTeamsConfig().set(
                "team-bans."
                        + team.teamId(),
                null
        );
        persist();
        return true;
    }

    public void setFriendlyFire(
            String teamId,
            boolean friendlyFire
    ) {
        TeamRecord old =
                teams.get(teamId);

        if (old == null) {
            return;
        }

        TeamRecord updated =
                new TeamRecord(
                        old.teamId(),
                        old.name(),
                        old.founder(),
                        friendlyFire
                );

        teams.put(
                teamId,
                updated
        );
        core.getTeamsConfig().set(
                "teams."
                        + teamId
                        + ".friendly-fire",
                friendlyFire
        );
        persist();
    }

    private boolean setMemberRoleInternal(
            TeamMemberRecord target,
            TeamRole role
    ) {
        if (target == null
                || role == null
                || role == TeamRole.FOUNDER) {
            return false;
        }

        TeamMemberRecord updated =
                new TeamMemberRecord(
                        target.teamId(),
                        target.playerId(),
                        role,
                        target.joinedAt()
                );

        members.put(
                target.playerId(),
                updated
        );
        writeMember(updated);
        persist();
        return true;
    }

    private boolean cannotModerate(
            UUID actorId,
            TeamMemberRecord actor,
            UUID targetId,
            TeamMemberRecord target
    ) {
        return actorId == null
                || targetId == null
                || actorId.equals(targetId)
                || differentTeam(actor, target)
                || !actor.role()
                .canModerate(
                        target.role()
                );
    }

    private boolean differentTeam(
            TeamMemberRecord first,
            TeamMemberRecord second
    ) {
        return first == null
                || second == null
                || !first.teamId()
                .equals(second.teamId());
    }

    private void removeMemberState(
            TeamMemberRecord member
    ) {
        UUID playerId =
                member.playerId();

        members.remove(playerId);
        teamChatEnabled.remove(playerId);

        LinkedHashSet<UUID> indexed =
                membersByTeam.get(
                        member.teamId()
                );

        if (indexed != null) {
            indexed.remove(playerId);

            if (indexed.isEmpty()) {
                membersByTeam.remove(
                        member.teamId()
                );
            }
        }

        core.getTeamsConfig().set(
                "teams."
                        + member.teamId()
                        + ".members."
                        + playerId,
                null
        );
        core.getTeamsConfig().set(
                "team-chat." + playerId,
                null
        );
    }

    private void removeExpiredBan(
            String teamId,
            UUID playerId,
            Map<UUID, TeamBanRecord> teamBans
    ) {
        teamBans.remove(playerId);
        core.getTeamsConfig().set(
                "team-bans."
                        + teamId
                        + "."
                        + playerId,
                null
        );

        if (teamBans.isEmpty()) {
            bans.remove(teamId);
        }
    }

    private void load() {
        teams.clear();
        members.clear();
        membersByTeam.clear();
        nameIndex.clear();
        bans.clear();
        teamChatEnabled.clear();

        FileConfiguration config =
                core.getTeamsConfig();
        ConfigurationSection teamsSection =
                config.getConfigurationSection(
                        "teams"
                );
        boolean changedStoredData = false;

        if (teamsSection != null) {
            for (String teamId :
                    teamsSection.getKeys(false)) {
                String path =
                        "teams." + teamId;
                String name =
                        config.getString(
                                path + ".name",
                                teamId
                        );
                String founderRaw =
                        config.getString(
                                path + ".founder",
                                null
                        );
                boolean friendlyFire =
                        config.getBoolean(
                                path
                                        + ".friendly-fire",
                                false
                        );

                if (founderRaw == null) {
                    continue;
                }

                UUID founder;

                try {
                    founder =
                            UUID.fromString(
                                    founderRaw
                            );
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                TeamRecord team =
                        new TeamRecord(
                                teamId,
                                name,
                                founder,
                                friendlyFire
                        );

                teams.put(teamId, team);
                nameIndex.put(
                        name.toLowerCase(
                                Locale.ROOT
                        ),
                        teamId
                );

                ConfigurationSection membersSection =
                        config.getConfigurationSection(
                                path
                                        + ".members"
                        );

                if (membersSection == null) {
                    continue;
                }

                for (String memberRaw :
                        membersSection
                                .getKeys(false)) {
                    try {
                        UUID memberId =
                                UUID.fromString(
                                        memberRaw
                                );
                        String memberPath =
                                path
                                        + ".members."
                                        + memberRaw;
                        String roleRaw =
                                config.getString(
                                        memberPath
                                                + ".role",
                                        "MEMBER"
                                );
                        long joinedAt =
                                config.getLong(
                                        memberPath
                                                + ".joined-at",
                                        System.currentTimeMillis()
                                );
                        TeamRole role =
                                TeamRole.fromStored(
                                        roleRaw
                                );

                        if (roleRaw.equalsIgnoreCase(
                                "ADMIN"
                        )) {
                            config.set(
                                    memberPath
                                            + ".role",
                                    TeamRole.MVP.name()
                            );
                            changedStoredData = true;
                        }

                        if (memberId.equals(founder)
                                && role
                                != TeamRole.FOUNDER) {
                            role =
                                    TeamRole.FOUNDER;
                            config.set(
                                    memberPath
                                            + ".role",
                                    TeamRole
                                            .FOUNDER
                                            .name()
                            );
                            changedStoredData = true;
                        } else if (!memberId.equals(founder)
                                && role
                                == TeamRole.FOUNDER) {
                            role = TeamRole.MVP;
                            config.set(
                                    memberPath
                                            + ".role",
                                    TeamRole.MVP
                                            .name()
                            );
                            changedStoredData = true;
                        }

                        TeamMemberRecord member =
                                new TeamMemberRecord(
                                        teamId,
                                        memberId,
                                        role,
                                        joinedAt
                                );

                        members.put(
                                memberId,
                                member
                        );
                        membersByTeam
                                .computeIfAbsent(
                                        teamId,
                                        ignored ->
                                                new LinkedHashSet<>()
                                )
                                .add(memberId);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        ConfigurationSection bansSection =
                config.getConfigurationSection(
                        "team-bans"
                );

        if (bansSection != null) {
            for (String teamId :
                    bansSection.getKeys(false)) {
                ConfigurationSection teamBansSection =
                        config.getConfigurationSection(
                                "team-bans."
                                        + teamId
                        );

                if (teamBansSection == null) {
                    continue;
                }

                for (String playerRaw :
                        teamBansSection
                                .getKeys(false)) {
                    try {
                        UUID playerId =
                                UUID.fromString(
                                        playerRaw
                                );
                        String path =
                                "team-bans."
                                        + teamId
                                        + "."
                                        + playerRaw;
                        UUID bannedBy =
                                UUID.fromString(
                                        config.getString(
                                                path
                                                        + ".banned-by",
                                                playerRaw
                                        )
                                );
                        long createdAt =
                                config.getLong(
                                        path
                                                + ".created-at",
                                        System.currentTimeMillis()
                                );
                        long expiresAt =
                                config.getLong(
                                        path
                                                + ".expires-at",
                                        createdAt
                                );

                        TeamBanRecord record =
                                new TeamBanRecord(
                                        teamId,
                                        playerId,
                                        bannedBy,
                                        createdAt,
                                        expiresAt
                                );

                        if (!record.expired()) {
                            bans.computeIfAbsent(
                                    teamId,
                                    ignored ->
                                            new HashMap<>()
                            ).put(
                                    playerId,
                                    record
                            );
                        } else {
                            config.set(
                                    path,
                                    null
                            );
                            changedStoredData = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        ConfigurationSection chatSection =
                config.getConfigurationSection(
                        "team-chat"
                );

        if (chatSection != null) {
            for (String uuidRaw :
                    chatSection.getKeys(false)) {
                try {
                    UUID uuid =
                            UUID.fromString(
                                    uuidRaw
                            );

                    if (config.getBoolean(
                            "team-chat."
                                    + uuidRaw,
                            false
                    )) {
                        teamChatEnabled.add(uuid);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        if (changedStoredData) {
            persist();
        }
    }

    private void writeTeam(TeamRecord team) {
        String path =
                "teams." + team.teamId();
        FileConfiguration config =
                core.getTeamsConfig();

        config.set(
                path + ".name",
                team.name()
        );
        config.set(
                path + ".founder",
                team.founder()
                        .toString()
        );
        config.set(
                path + ".friendly-fire",
                team.friendlyFire()
        );
    }

    private void writeMember(
            TeamMemberRecord member
    ) {
        String path =
                "teams."
                        + member.teamId()
                        + ".members."
                        + member.playerId();
        FileConfiguration config =
                core.getTeamsConfig();

        config.set(
                path + ".role",
                member.role().name()
        );
        config.set(
                path + ".joined-at",
                member.joinedAt()
        );
    }

    private void writeBan(
            TeamBanRecord record
    ) {
        String path =
                "team-bans."
                        + record.teamId()
                        + "."
                        + record.playerId();
        FileConfiguration config =
                core.getTeamsConfig();

        config.set(
                path + ".banned-by",
                record.bannedBy()
                        .toString()
        );
        config.set(
                path + ".created-at",
                record.createdAt()
        );
        config.set(
                path + ".expires-at",
                record.expiresAt()
        );
    }

    private void persist() {
        core.saveTeamsFile();
    }
}
