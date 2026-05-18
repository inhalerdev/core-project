package net.mineacle.core.sell.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.SellHistoryGui;
import net.mineacle.core.sell.model.SaleResult;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class SellGuiListener implements Listener {

    private final Core core;
    private final SellService sellService;
    private final Set<UUID> processingSellClose = new HashSet<>();

    public SellGuiListener(Core core, SellService sellService) {
        this.core = core;
        this.sellService = sellService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSellClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!isSellMenu(event.getView().getTitle())) {
            return;
        }

        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (isSellMenu(player.getOpenInventory().getTitle())) {
                sellService.applyWorthLore(player, player.getOpenInventory().getTopInventory());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSellDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!isSellMenu(event.getView().getTitle())) {
            return;
        }

        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (isSellMenu(player.getOpenInventory().getTitle())) {
                sellService.applyWorthLore(player, player.getOpenInventory().getTopInventory());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSellClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!isSellMenu(event.getView().getTitle())) {
            return;
        }

        if (!processingSellClose.add(player.getUniqueId())) {
            return;
        }

        SaleResult result = sellService.sellInventory(player.getUniqueId(), event.getInventory());

        for (ItemStack returned : result.returnedItems()) {
            returnItem(player, returned);
        }

        if (!result.soldAnything()) {
            processingSellClose.remove(player.getUniqueId());
            return;
        }

        String chat = sellService.message("sold-chat", "&#bbbbbbSold &#ff6fff%amount%x items &#bbbbbbfor &a+%money%")
                .replace("%amount%", String.valueOf(result.totalAmount()))
                .replace("%money%", sellService.format(result.totalCents()));

        String actionbar = sellService.message("sold-actionbar", "&a+%money%")
                .replace("%money%", sellService.format(result.totalCents()));

        player.sendMessage(chat);
        player.sendActionBar(actionBar(actionbar));
        SoundService.economyReceive(player, core);

        processingSellClose.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHistoryClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!SellHistoryGui.isTitle(title)) {
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

        int page = SellHistoryGui.currentPage(player);

        if (slot == SellHistoryGui.PREVIOUS_SLOT) {
            SoundService.guiClick(player, core);
            SellHistoryGui.open(core, player, sellService, page - 1);
            return;
        }

        if (slot == SellHistoryGui.SORT_SLOT) {
            SoundService.guiClick(player, core);
            SellHistoryGui.cycleSort(player);
            SellHistoryGui.open(core, player, sellService, 0);
            return;
        }

        if (slot == SellHistoryGui.NEXT_SLOT) {
            SoundService.guiClick(player, core);
            SellHistoryGui.open(core, player, sellService, page + 1);
        }
    }

    private boolean isSellMenu(String rawTitle) {
        String title = ChatColor.stripColor(rawTitle);
        String sellTitle = ChatColor.stripColor(SellGui.title(core));

        return title != null && sellTitle != null && title.equals(sellTitle);
    }

    private void returnItem(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        ItemStack clean = sellService.stripWorthLore(item);

        player.getInventory().addItem(clean).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
