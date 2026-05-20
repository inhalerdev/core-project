package net.mineacle.core.orders.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;

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
        return TextColor.color(cfg("orders.gui.titles.my-orders", "My Orders"));
    }

    public static boolean isTitle(String title) {
        return title != null && title.equals(ChatColor.stripColor(title()));
    }

    private static org.bukkit.inventory.ItemStack orderItem(OrderService service, OrderRecord order) {
        EconomyService economy = EconomyModule.economyService();
        String escrow = economy == null ? "$" + order.escrowRemainingCents() : economy.format(order.escrowRemainingCents());

        return OrdersMainGui.item(order.material(), "&d" + service.pretty(order.material()), List.of(
                cfg("orders.gui.my-order-lore.status", "&#bbbbbbStatus: %status%")
                        .replace("%status%", order.active() ? "&#ff88ffActive" : "&cClosed"),
                cfg("orders.gui.my-order-lore.delivered", "&#bbbbbbDelivered: &#ff88ff%delivered%&8/&#ff88ff%requested%")
                        .replace("%delivered%", String.valueOf(order.deliveredAmount()))
                        .replace("%requested%", String.valueOf(order.requestedAmount())),
                cfg("orders.gui.my-order-lore.escrow", "&#bbbbbbRefundable Escrow: &#ff88ff%escrow%")
                        .replace("%escrow%", escrow),
                "",
                order.active()
                        ? cfg("orders.gui.my-order-lore.click", "&#bbbbbbClick to collect, cancel, or refund")
                        : cfg("orders.gui.my-order-lore.closed", "&8Closed")
        ));
    }

    private static String cfg(String path, String fallback) {
        Core core = Core.instance();

        if (core == null) {
            return fallback;
        }

        return core.getConfig().getString(path, fallback);
    }
}
