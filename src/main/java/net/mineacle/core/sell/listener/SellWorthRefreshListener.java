package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;

public final class SellWorthRefreshListener implements Listener {

    private final Core core;

    public SellWorthRefreshListener(Core core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer(), 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refresh(player, 2L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            refresh(player, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        refresh(event.getPlayer(), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickup(PlayerPickupItemEvent event) {
        refresh(event.getPlayer(), 2L);
    }

    private void refresh(Player player, long delay) {
        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }

            player.updateInventory();
        }, delay);
    }
}
