package net.mineacle.core.hide;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.mineacle.core.Core;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Set;
import java.util.UUID;

@SuppressWarnings("unused")
public final class VanishListener
        implements Listener {

    private final Core core;
    private final VanishService service;

    public VanishListener(
            Core core,
            VanishService service
    ) {
        this.core = core;
        this.service = service;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onServerListPing(
            PaperServerListPingEvent event
    ) {
        Set<UUID> hidden =
                service.onlineVanishedSnapshot();

        if (hidden.isEmpty()) {
            return;
        }

        int reportedPlayers =
                event.getNumPlayers();

        if (reportedPlayers >= 0) {
            event.setNumPlayers(
                    Math.max(
                            0,
                            reportedPlayers - hidden.size()
                    )
            );
        }

        event.getListedPlayers().removeIf(
                listedPlayer ->
                        hidden.contains(
                                listedPlayer.id()
                        )
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.handleJoin(player);

        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (player.isOnline()) {
                        service.handleJoin(player);
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        scheduleReapply(event.getPlayer());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        scheduleReapply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleReapply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (service.isVanished(
                event.getEntity().getUniqueId()
        )) {
            event.deathMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(
            PlayerAdvancementDoneEvent event
    ) {
        if (service.isVanished(
                event.getPlayer().getUniqueId()
        )) {
            event.message(null);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onExperiencePickup(
            PlayerPickupExperienceEvent event
    ) {
        if (service.isVanished(
                event.getPlayer().getUniqueId()
        )) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player target
                && service.isVanished(target.getUniqueId())) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && service.isVanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player damager = playerDamager(event);

        if (damager != null
                && service.isVanished(damager.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && service.isVanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDrop(PlayerDropItemEvent event) {
        if (service.isVanished(
                event.getPlayer().getUniqueId()
        )) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBreak(BlockBreakEvent event) {
        if (service.interactionLocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent event) {
        if (service.interactionLocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInteract(PlayerInteractEvent event) {
        if (service.interactionLocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (service.interactionLocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (service.interactionLocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (service.interactionLocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private Player playerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }

        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }

        return null;
    }

    private void scheduleReapply(Player player) {
        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (player.isOnline()) {
                        service.reapply(player);
                    }
                }
        );
    }
}
