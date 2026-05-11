package net.mineacle.core.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mineacle.core.Core;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

public final class MineacleTeamsPlaceholderExpansion extends PlaceholderExpansion {

    private final Core core;
    private final TeamService teamService;

    public MineacleTeamsPlaceholderExpansion(Core core, TeamService teamService) {
        this.core = core;
        this.teamService = teamService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mineacleteams";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mineacle";
    }

    @Override
    public @NotNull String getVersion() {
        return core.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();
        String key = params.toLowerCase(Locale.ROOT);

        TeamRecord team = teamService.getTeamByPlayer(uuid);
        TeamMemberRecord member = teamService.getMember(uuid);

        return switch (key) {
            case "in_team", "has_team" -> team == null ? "false" : "true";

            case "name", "team", "team_name" -> team == null ? "" : team.name();

            case "role", "rank", "team_role", "team_rank" -> member == null ? "" : member.role().displayName();

            case "members", "member_count", "team_members", "team_member_count" -> {
                if (team == null) {
                    yield "0";
                }

                yield String.valueOf(teamService.getTeamMembers(team.teamId()).size());
            }

            case "max_members", "team_max_members" -> String.valueOf(teamService.maxMembers());

            case "pvp", "friendlyfire", "friendly_fire" -> {
                if (team == null) {
                    yield "Off";
                }

                yield team.friendlyFire() ? "On" : "Off";
            }

            case "chat", "team_chat" -> teamService.isTeamChatEnabled(uuid) ? "Enabled" : "Disabled";

            default -> null;
        };
    }
}