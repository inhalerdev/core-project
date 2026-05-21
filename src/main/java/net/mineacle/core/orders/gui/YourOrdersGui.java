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

        if (orders.isEmpty()) {
            inventory.setItem(22, OrdersMainGui.item(
                    org.bukkit.Material.BOOK,
                    "&dNo Orders",
                    List.of(
                            "&#bbbbbbYou have not created any orders",
                            "",
                            "&#bbbbbbCreate orders when you want",
                            "&#bbbbbbplayers to bring you items"
                    )
            ));
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

        long totalPayCents = order.requestedAmount() * order.pricePerItemCents();
        long remainingPayCents = order.remainingAmount() * order.pricePerItemCents();

        String totalPay = economy == null ? "$" + totalPayCents : economy.format(totalPayCents);
        String remainingPay = economy == null ? "$" + remainingPayCents : economy.format(remainingPayCents);

        return OrdersMainGui.item(
                order.material(),
                "&d" + service.pretty(order.material()),
                List.of(
                        "&#bbbbbbYou Ordered: &#ff88ff" + order.requestedAmount() + "x " + service.pretty(order.material()),
                        "&#bbbbbbYou Are Paying: &a" + totalPay,
                        "&#bbbbbbStill Available: &a" + remainingPay,
                        "&#bbbbbbDelivered: &#ff88ff" + order.deliveredAmount() + "&#bbbbbb/&#ff88ff" + order.requestedAmount(),
                        "&#bbbbbbStatus: " + (order.active() ? "&#ff88ffActive" : "&cClosed"),
                        "",
                        order.active()
                                ? "&#ff88ffClick to collect items or cancel"
                                : "&#bbbbbbClosed order"
                )
        );
    }

    private static String cfg(String path, String fallback) {
        Core core = Core.instance();

        if (core == null) {
            return fallback;
        }

        return core.getConfig().getString(path, fallback);
    }
}
