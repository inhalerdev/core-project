package net.mineacle.core.hide;

import net.mineacle.core.Core;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class HideListener implements Listener {

    private final Core core;
    private final HideService service;

    public HideListener(Core core, HideService service) {
        this.core = core;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            service.applyViewer(player);
            service.applyAll();
            NametagModule.refreshAll();
        }, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.show(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            service.applyViewer(player);
            service.apply(player);
            NametagModule.refreshAll();
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!service.disablePickup()) {
            return;
        }

        Entity entity = event.getEntity();

        if (!(entity instanceof Player player)) {
            return;
        }

        if (service.isHidden(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (!service.disableEntityTarget()) {
            return;
        }

        if (!(event.getEntity() instanceof Mob)) {
            return;
        }

        if (!(event.getTarget() instanceof Player player)) {
            return;
        }

        if (service.isHidden(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
