package net.mineacle.core.orders.gui;

import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiText;
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
                OrdersViewState.mainState(player);
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
                            Material.BARREL,
                            "&#B078FFNo Orders",
                            "&#bbbbbbNo matching player orders are open",
                            "",
                            "&#bbbbbbBuyers place bids for items",
                            "&#bbbbbbSellers fill the highest-paying requests"
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
                                Material.EMERALD
                        ),
                        OrdersGuiItems.cfg(
                                "orders.gui.buttons.refresh.name",
                                "&#B078FFRefresh"
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
                                "&#B078FFMy Orders"
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
                OrdersViewState.mainState(player);
        int maximum = maximumPage(
                filteredOrders(player, service).size()
        );
        state.page(
                Math.min(maximum, state.page() + 1)
        );
    }

    public static void previousPage(Player player) {
        OrdersViewState.MainState state =
                OrdersViewState.mainState(player);
        state.page(state.page() - 1);
    }

    public static void cycleSort(Player player) {
        OrdersViewState.mainState(player).cycleSort();
    }

    public static void cycleFilter(Player player) {
        OrdersViewState.mainState(player).cycleFilter();
    }

    public static void setSearch(
            Player player,
            String query
    ) {
        OrdersViewState.mainState(player).query(query);
    }

    public static void clearSearch(Player player) {
        OrdersViewState.mainState(player).clearQuery();
    }

    public static boolean hasSearch(Player player) {
        return OrdersViewState.mainState(player).hasQuery();
    }

    public static String search(Player player) {
        return OrdersViewState.mainState(player).query();
    }

    public static ItemStack orderItem(
            OrderService service,
            OrderRecord order
    ) {
        EconomyService economy =
                EconomyModule.economyService();
        long remainingEscrow = order.escrowRemainingCents();
        long bidEach = order.pricePerItemCents();
        String escrowText = economy == null
                ? "$" + remainingEscrow
                : economy.format(remainingEscrow);
        String eachText = economy == null
                ? "$" + bidEach
                : economy.format(bidEach);

        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbNeed: &#D0AFFF"
                        + order.remainingAmount()
                        + "x "
                        + service.pretty(order.material())
        );
        lore.add(
                "&#bbbbbbBid Each: &#11fc7b" + eachText
        );
        lore.add(
                "&#bbbbbbEscrow Remaining: &#11fc7b"
                        + escrowText
        );
        lore.add(
                "&#bbbbbbBuyer: &#D0AFFF"
                        + service.ownerDisplayName(order)
        );
        lore.add(
                "&#bbbbbbProgress: &#D0AFFF"
                        + order.deliveredAmount()
                        + "&#bbbbbb/&#D0AFFF"
                        + order.requestedAmount()
        );

        if (!order.exactLimitPrice()) {
            lore.add(
                    "&#bbbbbbPricing: &#D0AFFFLegacy total"
            );
        }

        lore.add("");
        lore.add("&#bbbbbbBring matching items");
        lore.add("&#D0AFFFClick to fill this order");

        return OrdersGuiItems.item(
                order.material(),
                "&#B078FF" + service.pretty(order.material()),
                lore
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
                OrdersViewState.mainState(player);
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
                "&#bbbbbbCurrent: &#D0AFFF"
                        + active.label()
        );
        lore.add("");

        for (OrdersViewState.SortMode mode
                : OrdersViewState.SortMode.values()) {
            lore.add(
                    (mode == active
                            ? "&#D0AFFF"
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
                        "&#B078FFSort"
                ),
                lore
        );
    }

    private static ItemStack filterItem(
            OrdersViewState.MainFilter active
    ) {
        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbCurrent: &#D0AFFF"
                        + active.label()
        );
        lore.add("");

        for (OrdersViewState.MainFilter mode
                : OrdersViewState.MainFilter.values()) {
            lore.add(
                    (mode == active
                            ? "&#D0AFFF"
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
                        "&#B078FFFilter"
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
                        "&#B078FFSearch"
                ),
                lore
        );
    }
}
