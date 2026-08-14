package net.mineacle.core.teams.listener;

import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.potion.PotionType;

import java.util.Collection;

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

        if (attacker != null
                && protectedFrom(
                attacker,
                damaged
        )) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onPotionSplash(
            PotionSplashEvent event
    ) {
        if (!(event.getPotion().getShooter()
                instanceof Player attacker)
                || !hasHarmfulEffect(
                event.getPotion().getEffects()
        )) {
            return;
        }

        for (LivingEntity affected :
                event.getAffectedEntities()) {
            if (affected instanceof Player player
                    && protectedFrom(
                    attacker,
                    player
            )) {
                event.setIntensity(
                        player,
                        0.0D
                );
            }
        }
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onAreaEffectCloud(
            AreaEffectCloudApplyEvent event
    ) {
        AreaEffectCloud cloud =
                event.getEntity();

        if (!(cloud.getSource()
                instanceof Player attacker)
                || !hasHarmfulEffect(cloud)) {
            return;
        }

        event.getAffectedEntities()
                .removeIf(
                        entity ->
                                entity instanceof Player player
                                        && protectedFrom(
                                        attacker,
                                        player
                                )
                );
    }

    private boolean protectedFrom(
            Player attacker,
            Player damaged
    ) {
        if (attacker == null
                || damaged == null
                || attacker.getUniqueId().equals(
                damaged.getUniqueId()
        )) {
            return false;
        }

        TeamRecord damagedTeam =
                teamService.getTeamByPlayer(
                        damaged.getUniqueId()
                );
        TeamRecord attackerTeam =
                teamService.getTeamByPlayer(
                        attacker.getUniqueId()
                );

        return damagedTeam != null
                && attackerTeam != null
                && damagedTeam.teamId().equals(
                attackerTeam.teamId()
        )
                && !damagedTeam.friendlyFire();
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
            case TNTPrimed tnt ->
                    tnt.getSource()
                            instanceof Player player
                            ? player
                            : null;
            default -> null;
        };
    }

    private boolean hasHarmfulEffect(
            AreaEffectCloud cloud
    ) {
        PotionType base =
                cloud.getBasePotionType();

        if (base != null
                && hasHarmfulEffect(
                base.getPotionEffects()
        )) {
            return true;
        }

        return hasHarmfulEffect(
                cloud.getCustomEffects()
        );
    }

    private boolean hasHarmfulEffect(
            Collection<PotionEffect> effects
    ) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getCategory()
                    == PotionEffectTypeCategory.HARMFUL) {
                return true;
            }
        }

        return false;
    }
}
