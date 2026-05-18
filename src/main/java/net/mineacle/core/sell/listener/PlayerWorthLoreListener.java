package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.SellHistoryGui;
import net.mineacle.core.sell.gui.SellMultiGui;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.InventoryView;

public final class PlayerWorthLoreListener implements Listener {

    private final Core core;
    private final SellService sellService;

    public PlayerWorthLoreListener(Core core, SellService sellService) {
        this.core = core;
        this.sellService = sellService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        updateLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        updateLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        updateLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickup(PlayerPickupItemEvent event) {
        updateLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            updateLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            updateLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            updateLater(player);
        }
    }

    private void updateLater(Player player) {
        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            sellService.applyWorthLore(player, player.getInventory());

            InventoryView view = player.getOpenInventory();

            if (view == null || isMineacleSystemMenu(view)) {
                return;
            }

            sellService.applyWorthLore(player, view.getTopInventory());
        });
    }

    private boolean isMineacleSystemMenu(InventoryView view) {
        String title = ChatColor.stripColor(view.getTitle());

        if (title == null) {
            return false;
        }

        String sellTitle = ChatColor.stripColor(SellGui.title(core));

        if (sellTitle != null && title.equals(sellTitle)) {
            return false;
        }

        return title.startsWith(WorthGui.TITLE_PREFIX)
                || title.startsWith(SellHistoryGui.TITLE_PREFIX)
                || title.equalsIgnoreCase(SellMultiGui.TITLE)
                || title.equalsIgnoreCase("Homes")
                || title.equalsIgnoreCase("Teleport Request")
                || title.equalsIgnoreCase("Confirm Request")
                || title.equalsIgnoreCase("Team Invites")
                || title.equalsIgnoreCase("Confirm Action")
                || title.startsWith("Member: ")
                || title.startsWith("Balance Top")
                || title.startsWith("Orders")
                || title.startsWith("Sell History")
                || title.startsWith("Item Prices");
    }
}
