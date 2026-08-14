package net.mineacle.core.teams.listener;

import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

@SuppressWarnings("unused")
public final class TeamCombatListener
        implements Listener {

    private final TeamService teamService;

    public TeamCombatListener(
            TeamService teamService
    ) {
        this.teamService = teamService;
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onDamage(
            EntityDamageByEntityEvent event
    ) {
        if (!(event.getEntity()
                instanceof Player damaged)) {
            return;
        }

        Player attacker =
                attacker(event);

        if (attacker == null
                || attacker.getUniqueId()
                .equals(
                        damaged.getUniqueId()
                )) {
            return;
        }

        TeamRecord damagedTeam =
                teamService.getTeamByPlayer(
                        damaged.getUniqueId()
                );
        TeamRecord attackerTeam =
                teamService.getTeamByPlayer(
                        attacker.getUniqueId()
                );

        if (damagedTeam == null
                || attackerTeam == null
                || !damagedTeam.teamId()
                .equals(
                        attackerTeam.teamId()
                )) {
            return;
        }

        if (!damagedTeam.friendlyFire()) {
            event.setCancelled(true);
        }
    }

    private Player attacker(
            EntityDamageByEntityEvent event
    ) {
        return switch (event.getDamager()) {
            case Player player -> player;
            case Projectile projectile ->
                    projectile.getShooter()
                            instanceof Player player
                            ? player
                            : null;
            case Tameable tameable ->
                    tameable.getOwner()
                            instanceof Player player
                            ? player
                            : null;
            default -> null;
        };
    }
}
