package net.mineacle.core.orders.gui;

import net.mineacle.core.Core;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Locale;

public final class YourOrdersGui {

    private YourOrdersGui() {
    }

    public static void open(Player player, OrderService service) {
        Inventory inventory = Bukkit.createInventory(null, 54, title());

        List<OrderRecord> orders = service.ownerOrders(player.getUniqueId());
        int slot = 0;

        for (OrderRecord order : orders) {
            if (slot >= OrdersMainGui.ORDERS_PER_PAGE) {
                break;
            }

            inventory.setItem(slot, orderItem(service, order));
            slot++;
        }

        player.openInventory(inventory);
    }

    public static String title() {
        Core core = Core.instance();

        if (core == null) {
            return "MY ORDERS";
        }

        return net.mineacle.core.common.text.TextColor.color(
                core.getConfig().getString("orders.gui.titles.my-orders", "MY ORDERS")
        );
    }

    public static boolean isTitle(String title) {
        if (title == null) {
            return false;
        }

        return title.equals(org.bukkit.ChatColor.stripColor(title()));
    }

    private static org.bukkit.inventory.ItemStack orderItem(OrderService service, OrderRecord order) {
        EconomyService economy = EconomyModule.economyService();
        String escrow = economy == null ? "$" + order.escrowRemainingCents() : economy.format(order.escrowRemainingCents());

        return OrdersMainGui.item(order.material(), "&a" + service.pretty(order.material()).toUpperCase(Locale.ROOT), List.of(
                "&fStatus: " + (order.active() ? "&aActive" : "&cClosed"),
                "&fDelivered: &a" + order.deliveredAmount() + "&8/&a" + order.requestedAmount(),
                "&fRefundable Escrow: &a" + escrow,
                "",
                order.active() ? "&fClick to cancel and refund" : "&8Closed"
        ));
    }
}
