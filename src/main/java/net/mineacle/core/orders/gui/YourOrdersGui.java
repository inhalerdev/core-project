package net.mineacle.core.orders.gui;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;

public final class YourOrdersGui {

    public static final String TITLE = "Your Orders";

    private YourOrdersGui() {
    }

    public static void open(Player player, OrderService service) {
        Inventory inventory = Bukkit.createInventory(null, 54, TITLE);

        List<OrderRecord> orders = service.ownerOrders(player.getUniqueId());
        int slot = 0;

        for (OrderRecord order : orders) {
            if (slot >= 45) {
                break;
            }

            inventory.setItem(slot, orderItem(service, order));
            slot++;
        }

        inventory.setItem(49, OrdersMainGui.item(Material.BARRIER, "&cClose", List.of(
                "&#ccccccPress ESC to close"
        )));

        player.openInventory(inventory);
    }

    private static org.bukkit.inventory.ItemStack orderItem(OrderService service, OrderRecord order) {
        EconomyService economy = EconomyModule.economyService();
        String escrow = economy == null ? "$" + order.escrowRemainingCents() : economy.format(order.escrowRemainingCents());

        return OrdersMainGui.item(order.material(), "&d" + service.pretty(order.material()), List.of(
                "&#ccccccStatus: " + (order.active() ? "&aActive" : "&cClosed"),
                "&#ccccccDelivered: &d" + order.deliveredAmount() + "&8/&d" + order.requestedAmount(),
                "&#ccccccRefundable Escrow: &a" + escrow,
                "",
                order.active() ? "&d➥ &#ccccccClick to cancel and refund" : "&#777777Closed"
        ));
    }
}
