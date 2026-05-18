package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class WorthGuiListener implements Listener {

    private final Core core;
    private final SellService sellService;

    public WorthGuiListener(Core core, SellService sellService) {
        this.core = core;
        this.sellService = sellService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!WorthGui.isTitle(title)) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        int page = WorthGui.currentPage(player);

        if (slot == WorthGui.PREVIOUS_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.open(core, player, sellService, page - 1);
            return;
        }

        if (slot == WorthGui.SORT_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.cycleSort(player);
            WorthGui.open(core, player, sellService, 0);
            return;
        }

        if (slot == WorthGui.FILTER_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.cycleFilter(player);
            WorthGui.open(core, player, sellService, 0);
            return;
        }

        if (slot == WorthGui.REFRESH_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.open(core, player, sellService, page);
            return;
        }

        if (slot == WorthGui.NEXT_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.open(core, player, sellService, page + 1);
        }
    }
}
