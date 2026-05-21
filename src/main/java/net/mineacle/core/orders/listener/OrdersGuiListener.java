package net.mineacle.core.orders.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.orders.gui.OrderConfirmGui;
import net.mineacle.core.orders.gui.OrderCreateGui;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.gui.YourOrdersGui;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class OrdersGuiListener implements Listener {

    private final Core core;
    private final OrderService service;

    public OrdersGuiListener(Core core, OrderService service) {
        this.core = core;
        this.service = service;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title == null) {
            return;
        }

        if (!OrdersMainGui.isTitle(title)
                && !YourOrdersGui.isTitle(title)
                && !OrderConfirmGui.isTitle(title)
                && !OrderCreateGui.isTitle(title)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        if (OrdersMainGui.isTitle(title)) {
            handleMain(player, slot);
            return;
        }

        if (YourOrdersGui.isTitle(title)) {
            handleMyOrders(player, slot);
            return;
        }

        if (OrderCreateGui.isTitle(title)) {
            handleCreate(player, slot);
            return;
        }

        handleConfirm(player, slot);
    }

    private void handleMain(Player player, int slot) {
        if (slot == OrdersMainGui.PREV_SLOT) {
            OrdersMainGui.previousPage(player);
            SoundService.guiClick(player, core);
            OrdersMainGui.open(player, service);
            return;
        }

        if (slot == OrdersMainGui.NEXT_SLOT) {
            OrdersMainGui.nextPage(player, service);
            SoundService.guiClick(player, core);
            OrdersMainGui.open(player, service);
            return;
        }

        if (slot == OrdersMainGui.SORT_SLOT) {
            OrdersMainGui.cycleSort(player);
            SoundService.guiClick(player, core);
            OrdersMainGui.open(player, service);
            return;
        }

        if (slot == OrdersMainGui.FILTER_SLOT) {
            OrdersMainGui.cycleFilter(player);
            SoundService.guiClick(player, core);
            OrdersMainGui.open(player, service);
            return;
        }

        if (slot == OrdersMainGui.SEARCH_SLOT) {
            SoundService.guiClick(player, core);
            OrderSearchInputListener.begin(player);
            player.closeInventory();
            player.sendMessage(TextColor.color("&#bbbbbbType an item name to search orders"));
            player.sendMessage(TextColor.color("&#bbbbbbType &#ff88ffclear &#bbbbbbto reset or &#ff88ffcancel &#bbbbbbto stop"));
            return;
        }

        if (slot == OrdersMainGui.REFRESH_SLOT) {
            SoundService.guiClick(player, core);
            OrdersMainGui.open(player, service);
            return;
        }

        if (slot == OrdersMainGui.MY_ORDERS_SLOT) {
            SoundService.guiClick(player, core);
            YourOrdersGui.open(player, service);
            return;
        }

        if (slot >= OrdersMainGui.ORDERS_PER_PAGE) {
            return;
        }

        List<OrderRecord> orders = OrdersMainGui.pageOrders(player, service);

        if (slot >= orders.size()) {
            return;
        }

        SoundService.guiClick(player, core);
        OrderConfirmGui.openDeliver(player, service, orders.get(slot));
    }

    private void handleMyOrders(Player player, int slot) {
        if (slot >= OrdersMainGui.ORDERS_PER_PAGE) {
            return;
        }

        List<OrderRecord> orders = service.ownerOrders(player.getUniqueId());

        if (slot >= orders.size()) {
            return;
        }

        OrderRecord order = orders.get(slot);

        if (order.deliveredAmount() > 0) {
            service.collect(player, order);
            YourOrdersGui.open(player, service);
            return;
        }

        if (!order.active()) {
            return;
        }

        SoundService.guiClick(player, core);
        OrderConfirmGui.openCancel(player, order);
    }

    private void handleCreate(Player player, int slot) {
        if (slot == OrderCreateGui.PREV_SLOT) {
            OrderCreateGui.previousPage(player);
            SoundService.guiClick(player, core);
            OrderCreateGui.open(player, service);
            return;
        }

        if (slot == OrderCreateGui.NEXT_SLOT) {
            OrderCreateGui.nextPage(player);
            SoundService.guiClick(player, core);
            OrderCreateGui.open(player, service);
            return;
        }

        if (slot == OrderCreateGui.FILTER_SLOT) {
            OrderCreateGui.cycleFilter(player);
            SoundService.guiClick(player, core);
            OrderCreateGui.open(player, service);
            return;
        }

        if (slot == OrderCreateGui.SEARCH_SLOT) {
            SoundService.guiClick(player, core);
            OrderSearchInputListener.beginCreateSearch(player);
            player.closeInventory();
            player.sendMessage(TextColor.color("&#bbbbbbType an item name to search"));
            player.sendMessage(TextColor.color("&#bbbbbbType &#ff88ffclear &#bbbbbbto reset or &#ff88ffcancel &#bbbbbbto stop"));
            return;
        }

        if (slot == OrderCreateGui.SELECTED_SLOT) {
            Material selected = OrderCreateGui.selected(player);

            if (selected == null) {
                return;
            }

            SoundService.guiClick(player, core);
            player.closeInventory();
            OrderCreateInputListener.beginAmount(player, selected);
            player.sendMessage(TextColor.color(""));
            player.sendMessage(TextColor.color("&#bbbbbbHow many &#ff88ff" + service.pretty(selected) + " &#bbbbbbdo you want?"));
            player.sendMessage(TextColor.color("&#bbbbbbExamples: &#ff88ff64&#bbbbbb, &#ff88ff2304"));
            player.sendMessage(TextColor.color("&#bbbbbbType &#ff88ffcancel &#bbbbbbto stop"));
            return;
        }

        if (slot >= OrderCreateGui.ITEMS_PER_PAGE) {
            return;
        }

        List<Material> materials = OrderCreateGui.pageItems(player);

        if (slot >= materials.size()) {
            return;
        }

        Material material = materials.get(slot);
        OrderCreateGui.select(player, material);
        SoundService.guiClick(player, core);
        player.closeInventory();
        OrderCreateInputListener.beginAmount(player, material);
        player.sendMessage(TextColor.color(""));
        player.sendMessage(TextColor.color("&#bbbbbbHow many &#ff88ff" + service.pretty(material) + " &#bbbbbbdo you want?"));
        player.sendMessage(TextColor.color("&#bbbbbbExamples: &#ff88ff64&#bbbbbb, &#ff88ff2304"));
        player.sendMessage(TextColor.color("&#bbbbbbType &#ff88ffcancel &#bbbbbbto stop"));
    }

    private void handleConfirm(Player player, int slot) {
        OrderConfirmGui.PendingAction pending = OrderConfirmGui.pending(player);

        if (pending == null) {
            player.closeInventory();
            return;
        }

        if (slot == OrderConfirmGui.CANCEL_SLOT) {
            OrderConfirmGui.clear(player);
            SoundService.guiCancel(player, core);
            player.closeInventory();
            return;
        }

        if (slot != OrderConfirmGui.CONFIRM_SLOT) {
            return;
        }

        OrderRecord order = service.get(pending.orderId());
        OrderConfirmGui.clear(player);
        player.closeInventory();

        if (order == null) {
            SoundService.guiError(player, core);
            return;
        }

        if (pending.type() == OrderConfirmGui.PendingType.DELIVER) {
            service.deliver(player, order);
            OrdersMainGui.open(player, service);
            return;
        }

        service.cancel(player, order);
        YourOrdersGui.open(player, service);
    }
}
