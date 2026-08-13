package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.SellHistoryGui;
import net.mineacle.core.sell.model.SaleResult;
import net.mineacle.core.sell.service.SellService;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

@SuppressWarnings("unused")
public final class SellGuiListener
        implements Listener {

    private final Core core;
    private final SellService sellService;
    private final Set<UUID> processingSellClose =
            new HashSet<>();
    private final Set<UUID> pendingSummaryRefresh =
            new HashSet<>();

    public SellGuiListener(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onSellClose(
            InventoryCloseEvent event
    ) {
        if (!(event.getPlayer()
                instanceof Player player)
                || !SellGui.isInventory(
                event.getInventory()
        )) {
            return;
        }

        UUID playerId =
                player.getUniqueId();
        pendingSummaryRefresh.remove(
                playerId
        );

        if (!processingSellClose.add(
                playerId
        )) {
            return;
        }

        Inventory inventory =
                event.getInventory();
        inventory.setItem(
                SellGui.SUMMARY_SLOT,
                null
        );

        /*
         * sellInventory() guarantees that exceptions are contained after the
         * economy-credit boundary. Therefore any RuntimeException that still
         * escapes here is necessarily pre-credit and the deposited items are
         * safe to restore from this immutable snapshot.
         */
        List<ItemStack> recoverySnapshot =
                snapshotDeposits(
                        inventory
                );

        try {
            SaleResult result;

            try {
                result =
                        sellService.sellInventory(
                                playerId,
                                inventory
                        );
            } catch (RuntimeException exception) {
                inventory.clear();

                for (ItemStack item
                        : recoverySnapshot) {
                    returnItem(
                            player,
                            item
                    );
                }

                core.getLogger().log(
                        Level.SEVERE,
                        "Sell GUI transaction failed before payout for "
                                + playerId
                                + " — deposited items were restored",
                        exception
                );
                player.sendMessage(
                        TextColor.color(
                                "&cCould not process this sale — your items were returned"
                        )
                );
                SoundService.guiError(
                        player,
                        core
                );
                return;
            }

            for (ItemStack returned
                    : result.returnedItems()) {
                returnItem(
                        player,
                        returned
                );
            }

            if (!result.failureMessage()
                    .isBlank()) {
                player.sendMessage(
                        TextColor.color(
                                result.failureMessage()
                        )
                );
                SoundService.guiError(
                        player,
                        core
                );
            }

            if (!result.soldAnything()) {
                return;
            }

            sendSaleResult(
                    player,
                    result
            );
        } finally {
            processingSellClose.remove(
                    playerId
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onSellClick(
            InventoryClickEvent event
    ) {
        if (!SellGui.isInventory(
                event.getView()
                        .getTopInventory()
        )) {
            return;
        }

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        if (event.getRawSlot()
                == SellGui.SUMMARY_SLOT) {
            event.setCancelled(true);
            event.setResult(
                    Event.Result.DENY
            );
            return;
        }

        refreshSellGui(
                player
        );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onSellDrag(
            InventoryDragEvent event
    ) {
        if (!SellGui.isInventory(
                event.getView()
                        .getTopInventory()
        )) {
            return;
        }

        if (event.getRawSlots()
                .contains(
                        SellGui.SUMMARY_SLOT
                )) {
            event.setCancelled(true);
            event.setResult(
                    Event.Result.DENY
            );
            return;
        }

        if (event.getWhoClicked()
                instanceof Player player) {
            refreshSellGui(
                    player
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onHistoryClick(
            InventoryClickEvent event
    ) {
        Inventory top =
                event.getView()
                        .getTopInventory();

        if (!SellHistoryGui.isInventory(
                top
        )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                Event.Result.DENY
        );

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        int slot =
                event.getRawSlot();
        int topSize =
                top.getSize();

        if (slot < 0
                || slot >= topSize) {
            return;
        }

        int page =
                SellHistoryGui.currentPage(
                        player
                );

        if ((slot == SellHistoryGui.PREVIOUS_SLOT
                || slot == SellHistoryGui.NEXT_SLOT)
                && SellHistoryGui
                .isDisabledNavigation(
                        event.getCurrentItem()
                )) {
            return;
        }

        if (slot
                == SellHistoryGui.PREVIOUS_SLOT) {
            SoundService.guiPage(
                    player,
                    core
            );
            openHistory(
                    player,
                    page - 1
            );
            return;
        }

        if (slot
                == SellHistoryGui.SORT_SLOT) {
            SoundService.guiSort(
                    player,
                    core
            );
            SellHistoryGui.cycleSort(
                    player,
                    event.isRightClick()
            );
            openHistory(
                    player,
                    0
            );
            return;
        }

        if (slot
                == SellHistoryGui.NEXT_SLOT) {
            SoundService.guiPage(
                    player,
                    core
            );
            openHistory(
                    player,
                    page + 1
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onHistoryDrag(
            InventoryDragEvent event
    ) {
        if (!SellHistoryGui.isInventory(
                event.getView()
                        .getTopInventory()
        )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                Event.Result.DENY
        );
    }

    private void openHistory(
            Player player,
            int page
    ) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> SellHistoryGui.open(
                        core,
                        player,
                        sellService,
                        page
                )
        );
    }

    /**
     * Coalesce arbitrary click/drag bursts into at most one summary rebuild
     * per player per server tick. Inventory#setItem performs the normal slot
     * synchronization, so a full Player#updateInventory resend is unnecessary.
     */
    private void refreshSellGui(
            Player player
    ) {
        UUID playerId =
                player.getUniqueId();

        if (!pendingSummaryRefresh.add(
                playerId
        )) {
            return;
        }

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        () -> {
                            pendingSummaryRefresh.remove(
                                    playerId
                            );

                            if (!player.isOnline()) {
                                return;
                            }

                            Inventory top =
                                    player.getOpenInventory()
                                            .getTopInventory();

                            if (!SellGui.isInventory(
                                    top
                            )) {
                                return;
                            }

                            SellGui.updateSummary(
                                    player,
                                    top,
                                    sellService
                            );
                        }
                );
    }

    private List<ItemStack> snapshotDeposits(
            Inventory inventory
    ) {
        List<ItemStack> snapshot =
                new ArrayList<>();

        for (ItemStack raw
                : inventory.getContents()) {
            if (raw == null
                    || raw.getType().isAir()) {
                continue;
            }

            ItemStack clean =
                    sellService.stripWorthLore(
                            raw
                    );

            if (clean != null
                    && !clean.getType().isAir()) {
                snapshot.add(
                        clean.clone()
                );
            }
        }

        return List.copyOf(snapshot);
    }

    private void returnItem(
            Player player,
            ItemStack item
    ) {
        if (item == null
                || item.getType().isAir()) {
            return;
        }

        ItemStack clean =
                sellService.stripWorthLore(
                        item
                );

        player.getInventory()
                .addItem(
                        clean
                )
                .values()
                .forEach(leftover ->
                        player.getWorld()
                                .dropItemNaturally(
                                        player.getLocation(),
                                        leftover
                                )
                );
    }

    private void sendSaleResult(
            Player player,
            SaleResult result
    ) {
        String chat =
                sellService.message(
                        "sold-chat",
                        "&#bbbbbbSold &#D0AFFF%amount%x items "
                                + "&#bbbbbbfor &#11fc7b+%money%"
                )
                        .replace(
                                "%amount%",
                                String.valueOf(
                                        result.totalAmount()
                                )
                        )
                        .replace(
                                "%money%",
                                sellService.format(
                                        result.totalCents()
                                )
                        );
        String actionBar =
                sellService.message(
                        "sold-actionbar",
                        "&#11fc7b+%money%"
                )
                        .replace(
                                "%money%",
                                sellService.format(
                                        result.totalCents()
                                )
                        );

        player.sendMessage(
                chat
        );
        player.sendActionBar(
                GuiText.component(
                        actionBar
                )
        );
        SoundService.economyReceive(
                player,
                core
        );
    }
}
