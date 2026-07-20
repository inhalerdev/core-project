package net.mineacle.core.orders.gui;

import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public final class YourOrdersGui {

    public static final int SIZE = 54;
    public static final int ORDERS_PER_PAGE = 45;

    private static final int[] TOOLBAR =
            CenteredToolbar.interiorSlots(SIZE, 3);

    public static final int PREVIOUS_SLOT =
            CenteredToolbar.previousSlot(SIZE);
    public static final int BACK_SLOT = TOOLBAR[0];
    public static final int REFRESH_SLOT = TOOLBAR[1];
    public static final int CREATE_SLOT = TOOLBAR[2];
    public static final int NEXT_SLOT =
            CenteredToolbar.nextSlot(SIZE);

    private YourOrdersGui() {
    }

    public static void open(
            Player player,
            OrderService service
    ) {
        List<OrderRecord> orders = service.ownerOrders(
                player.getUniqueId()
        );
        int maximumPage = maximumPage(orders.size());
        int page = Math.min(
                maximumPage,
                OrdersViewState.yourPage(player)
        );
        OrdersViewState.setYourPage(player, page);

        int start = (page - 1) * ORDERS_PER_PAGE;
        int end = Math.min(
                start + ORDERS_PER_PAGE,
                orders.size()
        );
        List<OrderRecord> pageOrders = start >= orders.size()
                ? List.of()
                : orders.subList(start, end);
        List<UUID> orderIds = pageOrders.stream()
                .map(OrderRecord::id)
                .toList();

        OrdersGuiHolder holder =
                OrdersGuiHolder.yourOrders(
                        page,
                        orderIds
                );
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                title(page)
        );
        holder.setInventory(inventory);

        for (int slot = 0;
             slot < pageOrders.size();
             slot++) {
            inventory.setItem(
                    slot,
                    orderItem(
                            service,
                            pageOrders.get(slot)
                    )
            );
        }

        if (orders.isEmpty()) {
            inventory.setItem(
                    22,
                    OrdersGuiItems.item(
                            Material.WRITABLE_BOOK,
                            "&dNo Orders",
                            "&#bbbbbbYou have not created any orders",
                            "",
                            "&#bbbbbbCreate an order when you want",
                            "&#bbbbbbplayers to deliver specific items"
                    )
            );
        }

        inventory.setItem(
                PREVIOUS_SLOT,
                OrdersGuiItems.navigation(
                        true,
                        page > 1,
                        Math.max(1, page - 1)
                )
        );

        inventory.setItem(
                BACK_SLOT,
                OrdersGuiItems.item(
                        Material.ARROW,
                        "&dBack to Orders",
                        "&#bbbbbbClick to return to open orders"
                )
        );
        inventory.setItem(
                REFRESH_SLOT,
                OrdersGuiItems.item(
                        Material.PAPER,
                        "&dRefresh",
                        "&#bbbbbbClick to refresh your orders"
                )
        );
        inventory.setItem(
                CREATE_SLOT,
                OrdersGuiItems.item(
                        Material.WRITABLE_BOOK,
                        "&dCreate Order",
                        "&#bbbbbbClick to create a new order"
                )
        );

        inventory.setItem(
                NEXT_SLOT,
                OrdersGuiItems.navigation(
                        false,
                        page < maximumPage,
                        Math.min(maximumPage, page + 1)
                )
        );

        player.openInventory(inventory);
    }

    public static void nextPage(
            Player player,
            OrderService service
    ) {
        int maximum = maximumPage(
                service.ownerOrders(
                        player.getUniqueId()
                ).size()
        );
        OrdersViewState.setYourPage(
                player,
                Math.min(
                        maximum,
                        OrdersViewState.yourPage(player) + 1
                )
        );
    }

    public static void previousPage(Player player) {
        OrdersViewState.setYourPage(
                player,
                OrdersViewState.yourPage(player) - 1
        );
    }

    public static String title(int page) {
        return OrdersGuiItems.cfg(
                "orders.gui.titles.my-orders",
                "My Orders (Page %page%)"
        ).replace(
                "%page%",
                String.valueOf(page)
        );
    }

    private static ItemStack orderItem(
            OrderService service,
            OrderRecord order
    ) {
        EconomyService economy =
                EconomyModule.economyService();
        String totalPay = economy == null
                ? "$" + order.totalEscrowCents()
                : economy.format(
                order.totalEscrowCents()
        );
        String refundable = economy == null
                ? "$" + order.escrowRemainingCents()
                : economy.format(
                order.escrowRemainingCents()
        );
        String status = order.active()
                ? "&#ff88ffActive"
                : "&#bbbbbbClosed";

        List<String> lore = new java.util.ArrayList<>();
        lore.add(
                "&#bbbbbbRequested: &#ff88ff"
                        + order.requestedAmount()
                        + "x "
                        + service.pretty(order.material())
        );
        lore.add(
                "&#bbbbbbTotal Escrow: &a" + totalPay
        );
        lore.add(
                "&#bbbbbbRefundable: &a" + refundable
        );
        lore.add(
                "&#bbbbbbDelivered: &#ff88ff"
                        + order.deliveredAmount()
                        + "&#bbbbbb/&#ff88ff"
                        + order.requestedAmount()
        );
        lore.add(
                "&#bbbbbbReady to Collect: &#ff88ff"
                        + order.collectableAmount()
        );
        lore.add(
                "&#bbbbbbStatus: " + status
        );
        lore.add("");

        if (order.collectableAmount() > 0) {
            lore.add(
                    "&#bbbbbbLeft-click to collect items"
            );
        }

        if (order.active()) {
            lore.add(
                    "&#bbbbbbRight-click to cancel and refund"
            );
        }

        if (order.collectableAmount() <= 0
                && !order.active()) {
            lore.add("&#bbbbbbThis order is closed");
        }

        return OrdersGuiItems.item(
                order.material(),
                "&d" + service.pretty(order.material()),
                lore
        );
    }

    private static int maximumPage(int size) {
        return Math.max(
                1,
                (int) Math.ceil(
                        size / (double) ORDERS_PER_PAGE
                )
        );
    }
}
