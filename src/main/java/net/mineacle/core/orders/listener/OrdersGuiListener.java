package net.mineacle.core.orders.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.gui.YourOrdersGui;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.ChatColor;
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

        if (!OrdersMainGui.isTitle(title) && !YourOrdersGui.isTitle(title)) {
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

        handleMyOrders(player, slot);
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
            player.closeInventory();
            player.sendMessage("§d/order search <item>");
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

        service.deliver(player, orders.get(slot));
        OrdersMainGui.open(player, service);
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

        if (!order.active()) {
            return;
        }

        service.cancel(player, order);
        YourOrdersGui.open(player, service);
    }
}
