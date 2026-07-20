package net.mineacle.core.orders.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.orders.gui.OrderConfirmGui;
import net.mineacle.core.orders.gui.OrderCreateGui;
import net.mineacle.core.orders.gui.OrdersGuiHolder;
import net.mineacle.core.orders.gui.OrdersGuiItems;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.gui.OrdersViewState;
import net.mineacle.core.orders.gui.YourOrdersGui;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.UUID;

public final class OrdersGuiListener
        implements Listener {

    private final Core core;
    private final OrderService service;

    public OrdersGuiListener(
            Core core,
            OrderService service
    ) {
        this.core = core;
        this.service = service;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView()
                .getTopInventory();

        if (!(top.getHolder()
                instanceof OrdersGuiHolder holder)) {
            return;
        }

        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= top.getSize()) {
            return;
        }

        if ((slot == OrdersMainGui.PREVIOUS_SLOT
                || slot == OrdersMainGui.NEXT_SLOT)
                && OrdersGuiItems.isDisabledNavigation(
                event.getCurrentItem()
        )) {
            return;
        }

        switch (holder.view()) {
            case MAIN -> handleMain(
                    player,
                    holder,
                    slot,
                    event.isRightClick()
            );
            case YOUR_ORDERS -> handleYourOrders(
                    player,
                    holder,
                    slot,
                    event.isRightClick()
            );
            case CREATE -> handleCreate(
                    player,
                    holder,
                    slot,
                    event.isRightClick()
            );
            case CONFIRM -> handleConfirm(
                    player,
                    holder,
                    slot
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onDrag(InventoryDragEvent event) {
        if (event.getView()
                .getTopInventory()
                .getHolder()
                instanceof OrdersGuiHolder) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        OrdersViewState.clear(playerId);
        OrderSearchInputListener.clear(playerId);
        OrderCreateInputListener.clear(playerId);
    }

    private void handleMain(
            Player player,
            OrdersGuiHolder holder,
            int slot,
            boolean rightClick
    ) {
        if (slot == OrdersMainGui.PREVIOUS_SLOT) {
            OrdersMainGui.previousPage(player);
            clickAndReopenMain(player);
            return;
        }

        if (slot == OrdersMainGui.NEXT_SLOT) {
            OrdersMainGui.nextPage(player, service);
            clickAndReopenMain(player);
            return;
        }

        if (slot == OrdersMainGui.SORT_SLOT) {
            OrdersMainGui.cycleSort(player);
            clickAndReopenMain(player);
            return;
        }

        if (slot == OrdersMainGui.FILTER_SLOT) {
            OrdersMainGui.cycleFilter(player);
            clickAndReopenMain(player);
            return;
        }

        if (slot == OrdersMainGui.REFRESH_SLOT) {
            clickAndReopenMain(player);
            return;
        }

        if (slot == OrdersMainGui.SEARCH_SLOT) {
            if (rightClick
                    && OrdersMainGui.hasSearch(player)) {
                OrdersMainGui.clearSearch(player);
                player.sendMessage(TextColor.color(
                        "&#bbbbbbOrder search cleared"
                ));
                SoundService.guiCancel(player, core);
                reopenMain(player);
                return;
            }

            beginMainSearch(player);
            return;
        }

        if (slot == OrdersMainGui.MY_ORDERS_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openChild(
                    core,
                    player,
                    () -> OrdersMainGui.open(
                            player,
                            service
                    ),
                    () -> YourOrdersGui.open(
                            player,
                            service
                    )
            );
            return;
        }

        if (slot >= OrdersMainGui.ORDERS_PER_PAGE) {
            return;
        }

        UUID orderId = holder.orderIdAt(slot);
        OrderRecord order = service.get(orderId);

        if (order == null || !order.active()) {
            error(
                    player,
                    "&cThat order is no longer available"
            );
            reopenMain(player);
            return;
        }

        if (order.ownerId().equals(
                player.getUniqueId()
        )) {
            error(
                    player,
                    "&cYou cannot deliver to your own order"
            );
            return;
        }

        if (service.countItems(
                player,
                order.material()
        ) <= 0) {
            error(
                    player,
                    "&cYou do not have the required item"
            );
            return;
        }

        SoundService.guiClick(player, core);
        MenuHistory.openChild(
                core,
                player,
                () -> OrdersMainGui.open(
                        player,
                        service
                ),
                () -> OrderConfirmGui.openDeliver(
                        player,
                        service,
                        order
                )
        );
    }

    private void handleYourOrders(
            Player player,
            OrdersGuiHolder holder,
            int slot,
            boolean rightClick
    ) {
        if (slot == YourOrdersGui.PREVIOUS_SLOT) {
            YourOrdersGui.previousPage(player);
            clickAndReopenYourOrders(player);
            return;
        }

        if (slot == YourOrdersGui.NEXT_SLOT) {
            YourOrdersGui.nextPage(player, service);
            clickAndReopenYourOrders(player);
            return;
        }

        if (slot == YourOrdersGui.BACK_SLOT) {
            SoundService.guiBack(player, core);

            if (!MenuHistory.back(core, player)) {
                MenuHistory.openRoot(
                        core,
                        player,
                        () -> OrdersMainGui.open(
                                player,
                                service
                        )
                );
            }

            return;
        }

        if (slot == YourOrdersGui.REFRESH_SLOT) {
            clickAndReopenYourOrders(player);
            return;
        }

        if (slot == YourOrdersGui.CREATE_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openChild(
                    core,
                    player,
                    () -> YourOrdersGui.open(
                            player,
                            service
                    ),
                    () -> OrderCreateGui.open(
                            player,
                            service
                    )
            );
            return;
        }

        if (slot >= YourOrdersGui.ORDERS_PER_PAGE) {
            return;
        }

        UUID orderId = holder.orderIdAt(slot);
        OrderRecord order = service.get(orderId);

        if (order == null) {
            error(
                    player,
                    "&cThat order is no longer available"
            );
            reopenYourOrders(player);
            return;
        }

        if (rightClick) {
            if (!order.active()) {
                error(player, "&cThat order is already closed");
                return;
            }

            SoundService.guiClick(player, core);
            MenuHistory.openChild(
                    core,
                    player,
                    () -> YourOrdersGui.open(
                            player,
                            service
                    ),
                    () -> OrderConfirmGui.openCancel(
                            player,
                            service,
                            order
                    )
            );
            return;
        }

        if (order.collectableAmount() > 0) {
            service.collect(player, order);
            reopenYourOrders(player);
            return;
        }

        if (order.active()) {
            error(
                    player,
                    "&cNo items are ready — right-click to cancel"
            );
            return;
        }

        error(player, "&cThat order is closed");
    }

    private void handleCreate(
            Player player,
            OrdersGuiHolder holder,
            int slot,
            boolean rightClick
    ) {
        if (slot == OrderCreateGui.PREVIOUS_SLOT) {
            OrderCreateGui.previousPage(player);
            clickAndReopenCreate(player);
            return;
        }

        if (slot == OrderCreateGui.NEXT_SLOT) {
            OrderCreateGui.nextPage(player);
            clickAndReopenCreate(player);
            return;
        }

        if (slot == OrderCreateGui.BACK_SLOT) {
            OrderCreateInputListener.clear(player);
            OrderCreateGui.clearSelected(player);
            SoundService.guiBack(player, core);

            if (!MenuHistory.back(core, player)) {
                MenuHistory.openRoot(
                        core,
                        player,
                        () -> OrdersMainGui.open(
                                player,
                                service
                        )
                );
            }

            return;
        }

        if (slot == OrderCreateGui.FILTER_SLOT) {
            OrderCreateGui.cycleFilter(player);
            clickAndReopenCreate(player);
            return;
        }

        if (slot == OrderCreateGui.SEARCH_SLOT) {
            if (rightClick
                    && OrderCreateGui.hasSearch(player)) {
                OrderCreateGui.clearSearch(player);
                player.sendMessage(TextColor.color(
                        "&#bbbbbbItem search cleared"
                ));
                SoundService.guiCancel(player, core);
                reopenCreate(player);
                return;
            }

            beginCreateSearch(player);
            return;
        }

        if (slot == OrderCreateGui.SELECTED_SLOT) {
            Material selected =
                    OrderCreateGui.selected(player);

            if (selected != null) {
                beginAmountInput(player, selected);
            }

            return;
        }

        if (slot >= OrderCreateGui.ITEMS_PER_PAGE) {
            return;
        }

        Material material = holder.materialAt(slot);

        if (material == null
                || material == Material.AIR) {
            return;
        }

        OrderCreateGui.select(player, material);
        beginAmountInput(player, material);
    }

    private void handleConfirm(
            Player player,
            OrdersGuiHolder holder,
            int slot
    ) {
        if (slot == OrderConfirmGui.CANCEL_SLOT) {
            OrderConfirmGui.disarm(holder);
            SoundService.guiCancel(player, core);
            player.closeInventory();
            return;
        }

        if (slot == OrderConfirmGui.ACTION_SLOT) {
            return;
        }

        if (slot != OrderConfirmGui.CONFIRM_SLOT) {
            return;
        }

        if (!OrderConfirmGui.confirmReady(holder)) {
            OrderConfirmGui.arm(
                    core,
                    player,
                    holder
            );
            return;
        }

        OrderConfirmGui.disarm(holder);
        OrderRecord order = service.get(
                holder.confirmationOrderId()
        );

        if (order == null) {
            error(
                    player,
                    "&cThat order is no longer available"
            );
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> OrdersMainGui.open(
                            player,
                            service
                    )
            );
            return;
        }

        if (holder.confirmation()
                == OrdersGuiHolder.Confirmation.DELIVER) {
            service.deliver(player, order);
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> OrdersMainGui.open(
                            player,
                            service
                    )
            );
            return;
        }

        service.cancel(player, order);
        MenuHistory.openRoot(
                core,
                player,
                () -> YourOrdersGui.open(
                        player,
                        service
                )
        );
    }

    private void beginMainSearch(Player player) {
        OrderSearchInputListener.beginMain(player);
        MenuHistory.closeForInput(core, player);
        SoundService.guiClick(player, core);

        player.sendMessage(TextColor.color(
                "&#bbbbbbType an item name to search orders"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbType &#ff88ffclear "
                        + "&#bbbbbbto reset or "
                        + "&#ff88ffcancel "
                        + "&#bbbbbbto stop"
        ));
    }

    private void beginCreateSearch(Player player) {
        OrderSearchInputListener.beginCreate(player);
        MenuHistory.closeForInput(core, player);
        SoundService.guiClick(player, core);

        player.sendMessage(TextColor.color(
                "&#bbbbbbType an item name to search"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbType &#ff88ffclear "
                        + "&#bbbbbbto reset or "
                        + "&#ff88ffcancel "
                        + "&#bbbbbbto stop"
        ));
    }

    private void beginAmountInput(
            Player player,
            Material material
    ) {
        OrderCreateInputListener.beginAmount(
                player,
                material
        );
        MenuHistory.closeForInput(core, player);
        SoundService.guiClick(player, core);

        player.sendMessage(TextColor.color(""));
        player.sendMessage(TextColor.color(
                "&#bbbbbbHow many &#ff88ff"
                        + service.pretty(material)
                        + " &#bbbbbbdo you want?"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbExamples: &#ff88ff64&#bbbbbb, "
                        + "&#ff88ff2304"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbType &#ff88ffcancel "
                        + "&#bbbbbbto stop"
        ));
    }

    private void clickAndReopenMain(Player player) {
        SoundService.guiClick(player, core);
        reopenMain(player);
    }

    private void clickAndReopenYourOrders(Player player) {
        SoundService.guiClick(player, core);
        reopenYourOrders(player);
    }

    private void clickAndReopenCreate(Player player) {
        SoundService.guiClick(player, core);
        reopenCreate(player);
    }

    private void reopenMain(Player player) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> OrdersMainGui.open(
                        player,
                        service
                )
        );
    }

    private void reopenYourOrders(Player player) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> YourOrdersGui.open(
                        player,
                        service
                )
        );
    }

    private void reopenCreate(Player player) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> OrderCreateGui.open(
                        player,
                        service
                )
        );
    }

    private void error(
            Player player,
            String message
    ) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }
}
