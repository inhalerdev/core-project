package net.mineacle.core.orders.gui;

import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiSearchLore;
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
import java.util.Locale;
import java.util.UUID;

public final class OrdersMainGui {

    public static final int SIZE = 54;
    public static final int ORDERS_PER_PAGE = 45;

    private static final int[] TOOLBAR =
            CenteredToolbar.interiorSlots(SIZE, 5);

    public static final int PREVIOUS_SLOT =
            CenteredToolbar.previousSlot(SIZE);
    public static final int SORT_SLOT = TOOLBAR[0];
    public static final int FILTER_SLOT = TOOLBAR[1];
    public static final int REFRESH_SLOT = TOOLBAR[2];
    public static final int SEARCH_SLOT = TOOLBAR[3];
    public static final int MY_ORDERS_SLOT = TOOLBAR[4];
    public static final int NEXT_SLOT =
            CenteredToolbar.nextSlot(SIZE);

    private OrdersMainGui() {
    }

    public static void open(
            Player player,
            OrderService service
    ) {
        OrdersViewState.MainState state =
                OrdersViewState.main(player);
        List<OrderRecord> orders =
                filteredOrders(player, service);
        int maximumPage = maximumPage(orders.size());

        if (state.page() > maximumPage) {
            state.page(maximumPage);
        }

        int page = state.page();
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
                OrdersGuiHolder.main(page, orderIds);
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
                            Material.BARREL,
                            "&dNo Orders",
                            "&#bbbbbbNo matching player orders are open",
                            "",
                            "&#bbbbbbPlayers create requests",
                            "&#bbbbbband sellers deliver items for money"
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
                SORT_SLOT,
                sortItem(state.sort())
        );
        inventory.setItem(
                FILTER_SLOT,
                filterItem(state.filter())
        );
        inventory.setItem(
                REFRESH_SLOT,
                OrdersGuiItems.item(
                        OrdersGuiItems.material(
                                "orders.gui.buttons.refresh.material",
                                Material.PAPER
                        ),
                        OrdersGuiItems.cfg(
                                "orders.gui.buttons.refresh.name",
                                "&dRefresh"
                        ),
                        OrdersGuiItems.lore(
                                "orders.gui.buttons.refresh.lore",
                                List.of(
                                        "&#bbbbbbClick to refresh orders"
                                )
                        )
                )
        );
        inventory.setItem(
                SEARCH_SLOT,
                searchItem(state)
        );
        inventory.setItem(
                MY_ORDERS_SLOT,
                OrdersGuiItems.playerHead(
                        player,
                        OrdersGuiItems.cfg(
                                "orders.gui.buttons.my-orders.name",
                                "&dMy Orders"
                        ),
                        OrdersGuiItems.lore(
                                "orders.gui.buttons.my-orders.lore",
                                List.of(
                                        "&#bbbbbbView orders you created",
                                        "&#bbbbbbCollect delivered items",
                                        "&#bbbbbbOr cancel active requests"
                                )
                        )
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
        OrdersViewState.MainState state =
                OrdersViewState.main(player);
        int maximum = maximumPage(
                filteredOrders(player, service).size()
        );
        state.page(
                Math.min(maximum, state.page() + 1)
        );
    }

    public static void previousPage(Player player) {
        OrdersViewState.MainState state =
                OrdersViewState.main(player);
        state.page(state.page() - 1);
    }

    public static void cycleSort(Player player) {
        OrdersViewState.main(player).cycleSort();
    }

    public static void cycleFilter(Player player) {
        OrdersViewState.main(player).cycleFilter();
    }

    public static void setSearch(
            Player player,
            String query
    ) {
        OrdersViewState.main(player).query(query);
    }

    public static void clearSearch(Player player) {
        OrdersViewState.main(player).clearQuery();
    }

    public static boolean hasSearch(Player player) {
        return OrdersViewState.main(player).hasQuery();
    }

    public static String search(Player player) {
        return OrdersViewState.main(player).query();
    }

    public static ItemStack orderItem(
            OrderService service,
            OrderRecord order
    ) {
        EconomyService economy =
                EconomyModule.economyService();
        long remainingPay = order.escrowRemainingCents();
        long oneItemPay = order.payoutFor(1);
        String totalText = economy == null
                ? "$" + remainingPay
                : economy.format(remainingPay);
        String eachText = economy == null
                ? "$" + oneItemPay
                : economy.format(oneItemPay);

        return OrdersGuiItems.item(
                order.material(),
                "&d" + service.pretty(order.material()),
                "&#bbbbbbNeed: &#ff88ff"
                        + order.remainingAmount()
                        + "x "
                        + service.pretty(order.material()),
                "&#bbbbbbPay Remaining: &a" + totalText,
                "&#bbbbbbNext Item Pays: &a" + eachText,
                "&#bbbbbbBuyer: "
                        + "&#ff88ff"
                        + service.ownerDisplayName(order),
                "&#bbbbbbProgress: "
                        + "&#ff88ff"
                        + order.deliveredAmount()
                        + "&#bbbbbb/&#ff88ff"
                        + order.requestedAmount(),
                "",
                "&#bbbbbbBring matching items",
                "&#ff88ffClick to deliver everything available"
        );
    }

    public static String title(int page) {
        return OrdersGuiItems.cfg(
                "orders.gui.titles.main",
                "Orders (Page %page%)"
        ).replace(
                "%page%",
                String.valueOf(page)
        );
    }

    private static List<OrderRecord> filteredOrders(
            Player player,
            OrderService service
    ) {
        OrdersViewState.MainState state =
                OrdersViewState.main(player);
        String query = state.query();

        return service.activeOrders().stream()
                .filter(
                        order -> state.filter()
                                .matches(order.material())
                )
                .filter(
                        order -> query.isBlank()
                                || matchesQuery(
                                service,
                                order,
                                query
                        )
                )
                .sorted(state.sort().comparator())
                .toList();
    }

    private static boolean matchesQuery(
            OrderService service,
            OrderRecord order,
            String query
    ) {
        String normalizedMaterial = order.material()
                .name()
                .toLowerCase(Locale.ROOT);
        String normalizedPretty = service.pretty(
                order.material()
        ).toLowerCase(Locale.ROOT)
                .replace(' ', '_');

        return normalizedMaterial.contains(query)
                || normalizedPretty.contains(query);
    }

    private static int maximumPage(int size) {
        return Math.max(
                1,
                (int) Math.ceil(
                        size / (double) ORDERS_PER_PAGE
                )
        );
    }

    private static ItemStack sortItem(
            OrdersViewState.SortMode active
    ) {
        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbCurrent: &#ff88ff"
                        + active.label()
        );
        lore.add("");

        for (OrdersViewState.SortMode mode
                : OrdersViewState.SortMode.values()) {
            lore.add(
                    (mode == active
                            ? "&#ff88ff"
                            : "&#bbbbbb")
                            + mode.label()
            );
        }

        lore.add("");
        lore.add("&#bbbbbbClick to change sort");

        return OrdersGuiItems.item(
                OrdersGuiItems.material(
                        "orders.gui.buttons.sort.material",
                        Material.ANVIL
                ),
                OrdersGuiItems.cfg(
                        "orders.gui.buttons.sort.name",
                        "&dSort"
                ),
                lore
        );
    }

    private static ItemStack filterItem(
            OrdersViewState.MainFilter active
    ) {
        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbCurrent: &#ff88ff"
                        + active.label()
        );
        lore.add("");

        for (OrdersViewState.MainFilter mode
                : OrdersViewState.MainFilter.values()) {
            lore.add(
                    (mode == active
                            ? "&#ff88ff"
                            : "&#bbbbbb")
                            + mode.label()
            );
        }

        lore.add("");
        lore.add("&#bbbbbbClick to change filter");

        return OrdersGuiItems.item(
                OrdersGuiItems.material(
                        "orders.gui.buttons.filter.material",
                        Material.HOPPER
                ),
                OrdersGuiItems.cfg(
                        "orders.gui.buttons.filter.name",
                        "&dFilter"
                ),
                lore
        );
    }

    private static ItemStack searchItem(
            OrdersViewState.MainState state
    ) {
        List<String> lore = state.hasQuery()
                ? GuiSearchLore.active(state.query())
                : GuiSearchLore.inactive("orders");

        return OrdersGuiItems.item(
                OrdersGuiItems.material(
                        "orders.gui.buttons.search.material",
                        Material.OAK_SIGN
                ),
                OrdersGuiItems.cfg(
                        "orders.gui.buttons.search.name",
                        "&dSearch"
                ),
                lore
        );
    }

}
