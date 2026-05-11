package net.mineacle.core.teams.listener;

import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public final class TeamCombatListener implements Listener {

    private final TeamService teamService;

    public TeamCombatListener(TeamService teamService) {
        this.teamService = teamService;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player damaged)) {
            return;
        }

        if (!(event.getDamager() instanceof Player damager)) {
            return;
        }

        TeamRecord damagedTeam = teamService.getTeamByPlayer(damaged.getUniqueId());
        TeamRecord damagerTeam = teamService.getTeamByPlayer(damager.getUniqueId());

        if (damagedTeam == null || damagerTeam == null) {
            return;
        }

        if (!damagedTeam.teamId().equals(damagerTeam.teamId())) {
            return;
        }

        if (!damagedTeam.friendlyFire()) {
            event.setCancelled(true);
        }
    }
}