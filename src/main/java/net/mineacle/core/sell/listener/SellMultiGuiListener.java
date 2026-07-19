package net.mineacle.core.sell.listener;

import net.mineacle.core.sell.gui.SellMultiGui;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class SellMultiGuiListener
        implements Listener {

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onInventoryClick(InventoryClickEvent event) {
        if (SellMultiGui.isInventory(
                event.getView().getTopInventory()
        )) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onInventoryDrag(InventoryDragEvent event) {
        if (SellMultiGui.isInventory(
                event.getView().getTopInventory()
        )) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }
}
