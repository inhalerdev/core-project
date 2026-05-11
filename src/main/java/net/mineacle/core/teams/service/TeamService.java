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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TeamService {

    private final Core core;
    private final Map<String, TeamRecord> teams = new HashMap<>();
    private final Map<UUID, TeamMemberRecord> members = new HashMap<>();
    private final Map<String, String> nameIndex = new HashMap<>();
    private final Map<String, Map<UUID, TeamBanRecord>> bans = new HashMap<>();
    private final Map<UUID, Boolean> teamChatEnabled = new HashMap<>();

    public TeamService(Core core) {
        this.core = core;
        load();
    }

    public int maxMembers() {
        return Math.max(1, core.getConfig().getInt("teams.max-members", 45));
    }

    public int banDays() {
        return Math.max(1, core.getConfig().getInt("teams.ban-days", 7));
    }

    public boolean hasTeam(UUID playerId) {
        return members.containsKey(playerId);
    }

    public TeamRecord getTeamByPlayer(UUID playerId) {
        TeamMemberRecord member = members.get(playerId);

        if (member == null) {
            return null;
        }

        return teams.get(member.teamId());
    }

    public TeamRecord getTeamById(String teamId) {
        return teams.get(teamId);
    }

    public TeamRecord getTeamByName(String name) {
        if (name == null) {
            return null;
        }

        String id = nameIndex.get(name.toLowerCase(Locale.ROOT));

        if (id == null) {
            return null;
        }

        return teams.get(id);
    }

    public TeamMemberRecord getMember(UUID playerId) {
        return members.get(playerId);
    }

    public List<UUID> getTeamMembers(String teamId) {
        List<TeamMemberRecord> records = new ArrayList<>();

        for (TeamMemberRecord member : members.values()) {
            if (member.teamId().equals(teamId)) {
                records.add(member);
            }
        }

        records.sort(Comparator
                .comparing((TeamMemberRecord member) -> member.role().ordinal())
                .thenComparing(TeamMemberRecord::joinedAt));

        List<UUID> ids = new ArrayList<>();

        for (TeamMemberRecord record : records) {
            ids.add(record.playerId());
        }

        return ids;
    }

    public boolean isFounder(UUID playerId) {
        TeamMemberRecord member = members.get(playerId);
        return member != null && member.role() == TeamRole.FOUNDER;
    }

    public boolean isAdmin(UUID playerId) {
        TeamMemberRecord member = members.get(playerId);
        return member != null && member.role().isAdmin();
    }

    public boolean isTeamChatEnabled(UUID playerId) {
        return teamChatEnabled.getOrDefault(playerId, false);
    }

    public boolean toggleTeamChat(UUID playerId) {
        boolean enabled = !isTeamChatEnabled(playerId);

        if (enabled) {
            teamChatEnabled.put(playerId, true);
        } else {
            teamChatEnabled.remove(playerId);
        }

        save();
        return enabled;
    }

    public void setTeamChat(UUID playerId, boolean enabled) {
        if (enabled) {
            teamChatEnabled.put(playerId, true);
        } else {
            teamChatEnabled.remove(playerId);
        }

        save();
    }

    public boolean isValidTeamName(String name) {
        if (name == null) {
            return false;
        }

        String cleaned = name.trim();

        if (cleaned.length() < 3 || cleaned.length() > 16) {
            return false;
        }

        return cleaned.matches("[A-Za-z0-9_]+");
    }

    public boolean createTeam(UUID founderId, String name) {
        if (hasTeam(founderId)) {
            return false;
        }

        if (!isValidTeamName(name)) {
            return false;
        }

        if (getTeamByName(name) != null) {
            return false;
        }

        String teamId = UUID.randomUUID().toString();
        TeamRecord team = new TeamRecord(teamId, name.trim(), founderId, false);
        TeamMemberRecord founder = new TeamMemberRecord(teamId, founderId, TeamRole.FOUNDER, System.currentTimeMillis());

        teams.put(teamId, team);
        members.put(founderId, founder);
        nameIndex.put(team.name().toLowerCase(Locale.ROOT), teamId);

        save();
        return true;
    }

    public boolean addMember(String teamId, UUID playerId) {
        TeamRecord team = teams.get(teamId);

        if (team == null) {
            return false;
        }

        if (hasTeam(playerId)) {
            return false;
        }

        if (isBanned(teamId, playerId)) {
            return false;
        }

        if (getTeamMembers(teamId).size() >= maxMembers()) {
            return false;
        }

        members.put(playerId, new TeamMemberRecord(teamId, playerId, TeamRole.MEMBER, System.currentTimeMillis()));
        save();
        return true;
    }

    public boolean removeMember(UUID playerId) {
        TeamMemberRecord member = members.get(playerId);

        if (member == null || member.role() == TeamRole.FOUNDER) {
            return false;
        }

        members.remove(playerId);
        teamChatEnabled.remove(playerId);
        save();
        return true;
    }

    public boolean kickMember(UUID actorId, UUID targetId) {
        TeamMemberRecord actor = members.get(actorId);
        TeamMemberRecord target = members.get(targetId);

        if (actor == null || target == null) {
            return false;
        }

        if (!actor.teamId().equals(target.teamId())) {
            return false;
        }

        if (actorId.equals(targetId)) {
            return false;
        }

        if (target.role() == TeamRole.FOUNDER) {
            return false;
        }

        if (actor.role() == TeamRole.ADMIN && target.role() != TeamRole.MEMBER) {
            return false;
        }

        if (!actor.role().isAdmin()) {
            return false;
        }

        members.remove(targetId);
        teamChatEnabled.remove(targetId);
        save();
        return true;
    }

    public boolean banMember(UUID actorId, UUID targetId) {
        TeamMemberRecord actor = members.get(actorId);
        TeamMemberRecord target = members.get(targetId);

        if (actor == null || target == null) {
            return false;
        }

        if (!actor.teamId().equals(target.teamId())) {
            return false;
        }

        if (actorId.equals(targetId)) {
            return false;
        }

        if (target.role() == TeamRole.FOUNDER) {
            return false;
        }

        if (actor.role() == TeamRole.ADMIN && target.role() != TeamRole.MEMBER) {
            return false;
        }

        if (!actor.role().isAdmin()) {
            return false;
        }

        long createdAt = System.currentTimeMillis();
        long expiresAt = createdAt + (banDays() * 24L * 60L * 60L * 1000L);

        bans.computeIfAbsent(target.teamId(), ignored -> new HashMap<>())
                .put(targetId, new TeamBanRecord(target.teamId(), targetId, actorId, createdAt, expiresAt));

        members.remove(targetId);
        teamChatEnabled.remove(targetId);
        save();
        return true;
    }

    public boolean isBanned(String teamId, UUID playerId) {
        Map<UUID, TeamBanRecord> teamBans = bans.get(teamId);

        if (teamBans == null) {
            return false;
        }

        TeamBanRecord record = teamBans.get(playerId);

        if (record == null) {
            return false;
        }

        if (record.expired()) {
            teamBans.remove(playerId);
            save();
            return false;
        }

        return true;
    }

    public boolean setMemberRole(UUID actorId, UUID targetId, TeamRole newRole) {
        TeamMemberRecord actor = members.get(actorId);
        TeamMemberRecord target = members.get(targetId);

        if (actor == null || target == null) {
            return false;
        }

        if (!actor.teamId().equals(target.teamId())) {
            return false;
        }

        if (actor.role() != TeamRole.FOUNDER) {
            return false;
        }

        if (target.role() == TeamRole.FOUNDER || newRole == TeamRole.FOUNDER) {
            return false;
        }

        members.put(targetId, new TeamMemberRecord(target.teamId(), target.playerId(), newRole, target.joinedAt()));
        save();
        return true;
    }

    public boolean transferFounder(UUID actorId, UUID targetId) {
        TeamMemberRecord actor = members.get(actorId);
        TeamMemberRecord target = members.get(targetId);

        if (actor == null || target == null) {
            return false;
        }

        if (!actor.teamId().equals(target.teamId())) {
            return false;
        }

        if (actor.role() != TeamRole.FOUNDER) {
            return false;
        }

        if (actorId.equals(targetId)) {
            return false;
        }

        TeamRecord team = teams.get(actor.teamId());

        if (team == null) {
            return false;
        }

        teams.put(team.teamId(), new TeamRecord(team.teamId(), team.name(), targetId, team.friendlyFire()));
        members.put(actorId, new TeamMemberRecord(actor.teamId(), actorId, TeamRole.ADMIN, actor.joinedAt()));
        members.put(targetId, new TeamMemberRecord(target.teamId(), targetId, TeamRole.FOUNDER, target.joinedAt()));

        save();
        return true;
    }

    public boolean disbandTeam(UUID actorId) {
        TeamRecord team = getTeamByPlayer(actorId);

        if (team == null || !team.founder().equals(actorId)) {
            return false;
        }

        teams.remove(team.teamId());
        nameIndex.remove(team.name().toLowerCase(Locale.ROOT));
        bans.remove(team.teamId());

        List<UUID> toRemove = new ArrayList<>();

        for (TeamMemberRecord member : members.values()) {
            if (member.teamId().equals(team.teamId())) {
                toRemove.add(member.playerId());
            }
        }

        for (UUID id : toRemove) {
            members.remove(id);
            teamChatEnabled.remove(id);
        }

        core.getTeamsConfig().set("team-homes." + team.teamId(), null);
        core.getTeamsConfig().set("team-bans." + team.teamId(), null);

        save();
        return true;
    }

    public boolean setFriendlyFire(String teamId, boolean friendlyFire) {
        TeamRecord old = teams.get(teamId);

        if (old == null) {
            return false;
        }

        teams.put(teamId, new TeamRecord(old.teamId(), old.name(), old.founder(), friendlyFire));
        save();
        return true;
    }

    public String formatTeamName(TeamRecord team) {
        if (team == null) {
            return "§7No Team";
        }

        return "§d" + team.name();
    }

    private void load() {
        teams.clear();
        members.clear();
        nameIndex.clear();
        bans.clear();
        teamChatEnabled.clear();

        FileConfiguration config = core.getTeamsConfig();
        ConfigurationSection teamsSection = config.getConfigurationSection("teams");

        if (teamsSection != null) {
            for (String teamId : teamsSection.getKeys(false)) {
                String path = "teams." + teamId;
                String name = config.getString(path + ".name", teamId);
                String founderRaw = config.getString(path + ".founder", null);
                boolean friendlyFire = config.getBoolean(path + ".friendly-fire", false);

                if (founderRaw == null) {
                    continue;
                }

                UUID founder;

                try {
                    founder = UUID.fromString(founderRaw);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                TeamRecord team = new TeamRecord(teamId, name, founder, friendlyFire);
                teams.put(teamId, team);
                nameIndex.put(name.toLowerCase(Locale.ROOT), teamId);

                ConfigurationSection membersSection = config.getConfigurationSection(path + ".members");

                if (membersSection == null) {
                    continue;
                }

                for (String memberRaw : membersSection.getKeys(false)) {
                    try {
                        UUID memberId = UUID.fromString(memberRaw);
                        String roleRaw = config.getString(path + ".members." + memberRaw + ".role", "MEMBER");
                        long joinedAt = config.getLong(path + ".members." + memberRaw + ".joined-at", System.currentTimeMillis());
                        TeamRole role = TeamRole.valueOf(roleRaw.toUpperCase(Locale.ROOT));

                        members.put(memberId, new TeamMemberRecord(teamId, memberId, role, joinedAt));
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        ConfigurationSection bansSection = config.getConfigurationSection("team-bans");

        if (bansSection != null) {
            for (String teamId : bansSection.getKeys(false)) {
                ConfigurationSection teamBansSection = config.getConfigurationSection("team-bans." + teamId);

                if (teamBansSection == null) {
                    continue;
                }

                for (String playerRaw : teamBansSection.getKeys(false)) {
                    try {
                        UUID playerId = UUID.fromString(playerRaw);
                        String path = "team-bans." + teamId + "." + playerRaw;
                        UUID bannedBy = UUID.fromString(config.getString(path + ".banned-by", playerRaw));
                        long createdAt = config.getLong(path + ".created-at", System.currentTimeMillis());
                        long expiresAt = config.getLong(path + ".expires-at", createdAt);
                        TeamBanRecord record = new TeamBanRecord(teamId, playerId, bannedBy, createdAt, expiresAt);

                        if (!record.expired()) {
                            bans.computeIfAbsent(teamId, ignored -> new HashMap<>()).put(playerId, record);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        ConfigurationSection chatSection = config.getConfigurationSection("team-chat");

        if (chatSection != null) {
            for (String uuidRaw : chatSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidRaw);

                    if (config.getBoolean("team-chat." + uuidRaw, false)) {
                        teamChatEnabled.put(uuid, true);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void save() {
        FileConfiguration config = core.getTeamsConfig();

        config.set("teams", null);
        config.set("team-bans", null);
        config.set("team-chat", null);

        for (TeamRecord team : teams.values()) {
            String path = "teams." + team.teamId();
            config.set(path + ".name", team.name());
            config.set(path + ".founder", team.founder().toString());
            config.set(path + ".friendly-fire", team.friendlyFire());

            for (TeamMemberRecord member : members.values()) {
                if (!member.teamId().equals(team.teamId())) {
                    continue;
                }

                String memberPath = path + ".members." + member.playerId();
                config.set(memberPath + ".role", member.role().name());
                config.set(memberPath + ".joined-at", member.joinedAt());
            }
        }

        for (Map.Entry<String, Map<UUID, TeamBanRecord>> teamEntry : bans.entrySet()) {
            for (TeamBanRecord record : teamEntry.getValue().values()) {
                if (record.expired()) {
                    continue;
                }

                String path = "team-bans." + record.teamId() + "." + record.playerId();
                config.set(path + ".banned-by", record.bannedBy().toString());
                config.set(path + ".created-at", record.createdAt());
                config.set(path + ".expires-at", record.expiresAt());
            }
        }

        for (Map.Entry<UUID, Boolean> entry : teamChatEnabled.entrySet()) {
            if (entry.getValue()) {
                config.set("team-chat." + entry.getKey(), true);
            }
        }

        core.saveTeamsFile();
    }
}