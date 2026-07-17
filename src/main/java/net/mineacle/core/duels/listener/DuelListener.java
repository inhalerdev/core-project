package net.mineacle.core.duels.listener;

import net.mineacle.core.duels.service.DuelService;
import net.mineacle.core.duels.service.FightTrackerService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class DuelListener implements Listener {

    private final DuelService duelService;
    private final FightTrackerService fightTracker;

    public DuelListener(DuelService duelService, FightTrackerService fightTracker) {
        this.duelService = duelService;
        this.fightTracker = fightTracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0.0D) {
            return;
        }

        Player attacker = responsiblePlayer(event.getDamager());

        if (attacker == null) {
            return;
        }

        fightTracker.recordDamage(attacker, victim);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player loser = event.getEntity();

        if (!(loser.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent)) {
            fightTracker.clearPlayer(loser);
            return;
        }

        Player mostRecentAttacker = responsiblePlayer(damageEvent.getDamager());
        Player officialKiller = loser.getKiller();
        Player killer = mostRecentAttacker != null ? mostRecentAttacker : officialKiller;

        if (killer == null) {
            fightTracker.clearPlayer(loser);
            return;
        }

        fightTracker.completeFight(loser, killer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        fightTracker.clearPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        duelService.removeFromQueue(event.getPlayer());
        fightTracker.clearPlayer(event.getPlayer());
    }

    private Player responsiblePlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }

        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();

            if (shooter instanceof Player player) {
                return player;
            }
        }

        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }

        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player player) {
            return player;
        }

        return null;
    }
}
