package net.mineacle.core.orders.gui;

import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
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
                GuiText.title(title(page))
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
                            "&#8436FENo Orders",
                            "&#bbbbbbYou have not created any orders",
                            "",
                            "&#bbbbbbCreate a buy limit when you want",
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
                        "&#8436FEBack to Orders",
                        "&#bbbbbbClick to return to open orders"
                )
        );
        inventory.setItem(
                REFRESH_SLOT,
                OrdersGuiItems.item(
                        Material.PAPER,
                        "&#8436FERefresh",
                        "&#bbbbbbClick to refresh your orders"
                )
        );
        inventory.setItem(
                CREATE_SLOT,
                OrdersGuiItems.item(
                        Material.WRITABLE_BOOK,
                        "&#8436FECreate Order",
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
        String bidEach = money(
                economy,
                order.pricePerItemCents()
        );
        String originalEscrow = money(
                economy,
                order.totalEscrowCents()
        );
        String spent = money(
                economy,
                order.actualSpentCents()
        );
        String refundable = money(
                economy,
                order.escrowRemainingCents()
        );
        String returned = money(
                economy,
                order.releasedEscrowCents()
        );
        String status = order.active()
                ? "&#B078FFActive"
                : "&#bbbbbbClosed";

        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbRequested: &#B078FF"
                        + order.requestedAmount()
                        + "x "
                        + service.pretty(order.material())
        );
        lore.add(
                (order.exactLimitPrice()
                        ? "&#bbbbbbBid Each: &#11fc7b"
                        : "&#bbbbbbApprox Each: &#11fc7b")
                        + bidEach
        );
        lore.add(
                "&#bbbbbbOriginal Escrow: &#11fc7b"
                        + originalEscrow
        );
        if (order.exactLimitPrice()) {
            lore.add(
                    "&#bbbbbbSpent: &#11fc7b" + spent
            );
        }

        lore.add(
                "&#bbbbbbRefundable: &#11fc7b" + refundable
        );

        if (order.exactLimitPrice()
                && order.releasedEscrowCents() > 0L) {
            lore.add(
                    "&#bbbbbbReturned: &#11fc7b" + returned
            );
        }

        lore.add(
                "&#bbbbbbDelivered: &#B078FF"
                        + order.deliveredAmount()
                        + "&#bbbbbb/&#B078FF"
                        + order.requestedAmount()
        );
        lore.add(
                "&#bbbbbbReady to Collect: &#B078FF"
                        + order.collectableAmount()
        );
        lore.add(
                "&#bbbbbbStatus: " + status
        );

        if (!order.exactLimitPrice()) {
            lore.add(
                    "&#bbbbbbPricing: &#D0AFFFLegacy total"
            );
        }

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
                "&#8436FE" + service.pretty(order.material()),
                lore
        );
    }

    private static String money(
            EconomyService economy,
            long cents
    ) {
        return economy == null
                ? "$" + cents
                : economy.format(cents);
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
