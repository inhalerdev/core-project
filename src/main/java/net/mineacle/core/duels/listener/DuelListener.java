package net.mineacle.core.duels.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.duels.service.DuelService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class DuelListener implements Listener {

    private final Core core;
    private final DuelService duelService;

    public DuelListener(Core core, DuelService duelService) {
        this.core = core;
        this.duelService = duelService;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        duelService.handleDeath(event.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        duelService.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!duelService.shouldCancelFrozenMove(player)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) {
            return;
        }

        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        event.setTo(from);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!duelService.shouldCancelFrozenDamage(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!duelService.shouldCancelFrozenInteract(event.getPlayer())) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(TextColor.color("&#bbbbbbFight has not started yet"));
    }
}
