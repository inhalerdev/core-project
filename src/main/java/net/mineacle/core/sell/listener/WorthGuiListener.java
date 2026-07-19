package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class WorthGuiListener implements Listener {

    private final Core core;
    private final SellService sellService;

    public WorthGuiListener(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onInventoryClick(InventoryClickEvent event) {
        if (!WorthGui.isInventory(
                event.getView().getTopInventory()
        )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        int page = WorthGui.currentPage(player);

        if (slot == WorthGui.PREVIOUS_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.open(
                    core,
                    player,
                    sellService,
                    page - 1
            );
            return;
        }

        if (slot == WorthGui.SORT_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.cycleSort(player);
            WorthGui.open(
                    core,
                    player,
                    sellService,
                    0
            );
            return;
        }

        if (slot == WorthGui.FILTER_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.cycleFilter(player);
            WorthGui.open(
                    core,
                    player,
                    sellService,
                    0
            );
            return;
        }

        if (slot == WorthGui.REFRESH_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.clearCatalogCache();
            WorthGui.open(
                    core,
                    player,
                    sellService,
                    page
            );
            return;
        }

        if (slot == WorthGui.NEXT_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.open(
                    core,
                    player,
                    sellService,
                    page + 1
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onInventoryDrag(InventoryDragEvent event) {
        if (WorthGui.isInventory(
                event.getView().getTopInventory()
        )) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        WorthGui.clear(event.getPlayer());
    }
}
