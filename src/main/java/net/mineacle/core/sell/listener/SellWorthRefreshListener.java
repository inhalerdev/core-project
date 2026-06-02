package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class SellWorthRefreshListener implements Listener {

    private final Core core;

    public SellWorthRefreshListener(Core core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer(), 10L);
        refresh(event.getPlayer(), 30L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refresh(player, 1L);
            refresh(player, 3L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            refresh(player, 1L);
            refresh(player, 3L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            refresh(player, 1L);
            refresh(player, 3L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refresh(player, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        refresh(event.getPlayer(), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            refresh(player, 1L);
            refresh(player, 3L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        refresh(event.getPlayer(), 1L);
        refresh(event.getPlayer(), 3L);
    }

    private void refresh(Player player, long delay) {
        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }

            player.updateInventory();
        }, delay);
    }
}
