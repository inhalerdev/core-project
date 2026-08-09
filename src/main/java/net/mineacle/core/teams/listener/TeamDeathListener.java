package net.mineacle.core.teams.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

public final class TeamDeathListener implements Listener {

    private final Core core;
    private final TeamService teamService;

    public TeamDeathListener(Core core, TeamService teamService) {
        this.core = core;
        this.teamService = teamService;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player deadPlayer = event.getEntity();
        TeamRecord team = teamService.getTeamByPlayer(deadPlayer.getUniqueId());

        if (team == null) {
            return;
        }

        Player killer = deadPlayer.getKiller();

        String deadDisplayName = DisplayNames.displayName(deadPlayer);

        String message;

        if (killer != null) {
            String killerDisplayName = DisplayNames.displayName(killer);

            message = core.getMessage("teams.death.killed")
                    .replace("%player%", deadDisplayName)
                    .replace("%killer%", killerDisplayName)
                    .replace("%team%", team.name());
        } else {
            message = core.getMessage("teams.death.died")
                    .replace("%player%", deadDisplayName)
                    .replace("%team%", team.name());
        }

        for (UUID memberId : teamService.getTeamMembers(team.teamId())) {
            Player member = Bukkit.getPlayer(memberId);

            if (member == null || !member.isOnline()) {
                continue;
            }

            member.sendMessage(message);
        }
    }
}