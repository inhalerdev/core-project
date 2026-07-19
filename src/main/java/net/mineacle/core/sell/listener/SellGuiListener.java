package net.mineacle.core.sell.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.SellHistoryGui;
import net.mineacle.core.sell.model.SaleResult;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
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
    private final Set<UUID> processingSellClose =
            new HashSet<>();

    public SellGuiListener(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSellClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !SellGui.isInventory(event.getInventory())) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (!processingSellClose.add(playerId)) {
            return;
        }

        try {
            Inventory inventory = event.getInventory();
            inventory.setItem(SellGui.SUMMARY_SLOT, null);

            SaleResult result = sellService.sellInventory(
                    playerId,
                    inventory
            );

            for (ItemStack returned
                    : result.returnedItems()) {
                returnItem(player, returned);
            }

            if (!result.failureMessage().isBlank()) {
                player.sendMessage(
                        TextColor.color(
                                result.failureMessage()
                        )
                );
                SoundService.guiError(player, core);
            }

            if (!result.soldAnything()) {
                return;
            }

            String chat = sellService.message(
                    "sold-chat",
                    "&#bbbbbbSold &#ff88ff%amount%x items "
                            + "&#bbbbbbfor &a+%money%"
            )
                    .replace(
                            "%amount%",
                            String.valueOf(result.totalAmount())
                    )
                    .replace(
                            "%money%",
                            sellService.format(
                                    result.totalCents()
                            )
                    );
            String actionBar = sellService.message(
                    "sold-actionbar",
                    "&a+%money%"
            ).replace(
                    "%money%",
                    sellService.format(result.totalCents())
            );

            player.sendMessage(chat);
            player.sendActionBar(component(actionBar));
            SoundService.economyReceive(player, core);
        } finally {
            processingSellClose.remove(playerId);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onSellClick(InventoryClickEvent event) {
        if (!SellGui.isInventory(
                event.getView().getTopInventory()
        )) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getRawSlot() == SellGui.SUMMARY_SLOT) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
            return;
        }

        refreshSellGui(player);
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onSellDrag(InventoryDragEvent event) {
        if (!SellGui.isInventory(
                event.getView().getTopInventory()
        )) {
            return;
        }

        if (event.getRawSlots()
                .contains(SellGui.SUMMARY_SLOT)) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
            return;
        }

        if (event.getWhoClicked() instanceof Player player) {
            refreshSellGui(player);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onHistoryClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(
                event.getView().getTitle()
        );

        if (!SellHistoryGui.isTitle(title)) {
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

        int page = SellHistoryGui.currentPage(player);

        if (slot == SellHistoryGui.PREVIOUS_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openWithoutBackTrigger(
                    core,
                    player,
                    () -> SellHistoryGui.open(
                            core,
                            player,
                            sellService,
                            page - 1
                    )
            );
            return;
        }

        if (slot == SellHistoryGui.SORT_SLOT) {
            SoundService.guiClick(player, core);
            SellHistoryGui.cycleSort(player);
            MenuHistory.openWithoutBackTrigger(
                    core,
                    player,
                    () -> SellHistoryGui.open(
                            core,
                            player,
                            sellService,
                            0
                    )
            );
            return;
        }

        if (slot == SellHistoryGui.NEXT_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openWithoutBackTrigger(
                    core,
                    player,
                    () -> SellHistoryGui.open(
                            core,
                            player,
                            sellService,
                            page + 1
                    )
            );
        }
    }

    private void refreshSellGui(Player player) {
        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    Inventory top = player.getOpenInventory()
                            .getTopInventory();

                    if (!SellGui.isInventory(top)) {
                        return;
                    }

                    SellGui.updateSummary(
                            player,
                            top,
                            sellService
                    );
                    player.updateInventory();
                }
        );
    }

    private void returnItem(
            Player player,
            ItemStack item
    ) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        ItemStack clean = sellService.stripWorthLore(item);

        player.getInventory()
                .addItem(clean)
                .values()
                .forEach(leftover ->
                        player.getWorld().dropItemNaturally(
                                player.getLocation(),
                                leftover
                        )
                );
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }
}
