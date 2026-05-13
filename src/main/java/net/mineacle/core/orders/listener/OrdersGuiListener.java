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

        if (!title.equals(OrdersMainGui.TITLE) && !title.equals(YourOrdersGui.TITLE)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        if (title.equals(OrdersMainGui.TITLE)) {
            handleMain(player, slot);
            return;
        }

        handleYourOrders(player, slot);
    }

    private void handleMain(Player player, int slot) {
        if (slot == 45) {
            SoundService.guiClick(player, core);
            YourOrdersGui.open(player, service);
            return;
        }

        if (slot == 49) {
            SoundService.guiClick(player, core);
            OrdersMainGui.open(player, service);
            return;
        }

        if (slot == 53) {
            SoundService.guiClick(player, core);
            player.closeInventory();
            player.sendMessage("§d/order create <amount> <price_each>");
            return;
        }

        if (slot >= 45) {
            return;
        }

        List<OrderRecord> orders = service.activeOrders();

        if (slot >= orders.size()) {
            return;
        }

        service.deliver(player, orders.get(slot));
        OrdersMainGui.open(player, service);
    }

    private void handleYourOrders(Player player, int slot) {
        if (slot == 49) {
            player.closeInventory();
            return;
        }

        if (slot >= 45) {
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
