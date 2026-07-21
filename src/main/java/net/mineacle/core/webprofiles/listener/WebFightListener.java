package net.mineacle.core.webprofiles.listener;

import net.mineacle.core.webprofiles.service.WebFightService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class WebFightListener
        implements Listener {

    private final WebFightService fightService;

    public WebFightListener(
            WebFightService fightService
    ) {
        this.fightService = fightService;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onDamage(
            EntityDamageByEntityEvent event
    ) {
        if (!(event.getEntity()
                instanceof Player victim)) {
            return;
        }

        Player attacker = attacker(
                event.getDamager()
        );

        if (attacker == null) {
            return;
        }

        fightService.recordDamage(
                attacker,
                victim
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        fightService.recordDeath(
                event.getEntity()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(
            PlayerChangedWorldEvent event
    ) {
        fightService.removePlayer(
                event.getPlayer().getUniqueId()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        fightService.removePlayer(
                event.getPlayer().getUniqueId()
        );
    }

    private Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }

        if (!(damager instanceof Projectile projectile)) {
            return null;
        }

        ProjectileSource shooter =
                projectile.getShooter();

        return shooter instanceof Player player
                ? player
                : null;
    }
}
