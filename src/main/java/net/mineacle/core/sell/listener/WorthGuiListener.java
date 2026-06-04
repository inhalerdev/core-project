package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.Locale;

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
        event.setResult(Event.Result.DENY);

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

        if (slot == WorthGui.SEARCH_SLOT) {
            SoundService.guiClick(player, core);
            player.setMetadata(WorthGui.META_SEARCHING, new FixedMetadataValue(core, true));
            player.closeInventory();
            player.sendMessage(TextColor.color("&dMineacle &8» &#bbbbbbType an item name to search, &dclear &#bbbbbbto clear, or &dcancel &#bbbbbbto cancel"));
            return;
        }

        if (slot == WorthGui.NEXT_SLOT) {
            SoundService.guiClick(player, core);
            WorthGui.open(core, player, sellService, page + 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (!player.hasMetadata(WorthGui.META_SEARCHING)) {
            return;
        }

        event.setCancelled(true);
        player.removeMetadata(WorthGui.META_SEARCHING, core);

        String message = event.getMessage() == null ? "" : event.getMessage().trim();
        String lower = message.toLowerCase(Locale.ROOT);

        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (lower.equals("cancel") || lower.equals("cancelled")) {
                player.sendMessage(TextColor.color("&dMineacle &8» &#bbbbbbItem Prices search cancelled"));
                WorthGui.open(core, player, sellService, WorthGui.currentPage(player));
                return;
            }

            if (lower.equals("clear")) {
                WorthGui.clearSearch(player);
                player.sendMessage(TextColor.color("&dMineacle &8» &#bbbbbbItem Prices search cleared"));
                WorthGui.open(core, player, sellService, 0);
                return;
            }

            if (message.isBlank()) {
                player.sendMessage(TextColor.color("&dMineacle &8» &#bbbbbbItem Prices search cancelled"));
                WorthGui.open(core, player, sellService, WorthGui.currentPage(player));
                return;
            }

            WorthGui.setSearch(player, message);
            player.sendMessage(TextColor.color("&dMineacle &8» &#bbbbbbSearching Item Prices for &d" + message));
            WorthGui.open(core, player, sellService, 0);
        });
    }
}
