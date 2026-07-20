package net.mineacle.core.orders.gui;

import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OrderCreateGui {

    public static final int SIZE = 54;
    public static final int ITEMS_PER_PAGE = 45;

    private static final int[] TOOLBAR =
            CenteredToolbar.interiorSlots(SIZE, 4);

    public static final int PREVIOUS_SLOT =
            CenteredToolbar.previousSlot(SIZE);
    public static final int BACK_SLOT = TOOLBAR[0];
    public static final int FILTER_SLOT = TOOLBAR[1];
    public static final int SELECTED_SLOT = TOOLBAR[2];
    public static final int SEARCH_SLOT = TOOLBAR[3];
    public static final int NEXT_SLOT =
            CenteredToolbar.nextSlot(SIZE);

    private OrderCreateGui() {
    }

    public static void open(
            Player player,
            OrderService service
    ) {
        OrdersViewState.CreateState state =
                OrdersViewState.create(player);
        List<Material> materials = filteredItems(player);
        int maximumPage = maximumPage(materials.size());

        if (state.page() > maximumPage) {
            state.page(maximumPage);
        }

        int page = state.page();
        int start = (page - 1) * ITEMS_PER_PAGE;
        int end = Math.min(
                start + ITEMS_PER_PAGE,
                materials.size()
        );
        List<Material> pageItems = start >= materials.size()
                ? List.of()
                : materials.subList(start, end);

        OrdersGuiHolder holder =
                OrdersGuiHolder.create(page, pageItems);
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                title(page)
        );
        holder.setInventory(inventory);

        for (int slot = 0;
             slot < pageItems.size();
             slot++) {
            Material material = pageItems.get(slot);

            inventory.setItem(
                    slot,
                    OrdersGuiItems.item(
                            material,
                            "&d" + service.pretty(material),
                            "&#bbbbbbChoose this item for your order",
                            "",
                            "&#bbbbbbPlayers will deliver this item",
                            "&#bbbbbband receive the total pay you set",
                            "",
                            "&#ff88ffClick to choose"
                    )
            );
        }

        if (materials.isEmpty()) {
            inventory.setItem(
                    22,
                    OrdersGuiItems.item(
                            Material.BARRIER,
                            "&cNo Items Found",
                            "&#bbbbbbTry another search",
                            "&#bbbbbbOr clear the current filter"
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
                FILTER_SLOT,
                filterItem(state.filter())
        );
        inventory.setItem(
                SELECTED_SLOT,
                selectedItem(state, service)
        );
        inventory.setItem(
                SEARCH_SLOT,
                searchItem(state)
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

    public static void nextPage(Player player) {
        OrdersViewState.CreateState state =
                OrdersViewState.create(player);
        int maximum = maximumPage(
                filteredItems(player).size()
        );
        state.page(
                Math.min(maximum, state.page() + 1)
        );
    }

    public static void previousPage(Player player) {
        OrdersViewState.CreateState state =
                OrdersViewState.create(player);
        state.page(state.page() - 1);
    }

    public static void cycleFilter(Player player) {
        OrdersViewState.create(player).cycleFilter();
    }

    public static void setSearch(
            Player player,
            String query
    ) {
        OrdersViewState.create(player).query(query);
    }

    public static void clearSearch(Player player) {
        OrdersViewState.create(player).clearQuery();
    }

    public static boolean hasSearch(Player player) {
        return OrdersViewState.create(player).hasQuery();
    }

    public static Material selected(Player player) {
        return OrdersViewState.create(player).selected();
    }

    public static void select(
            Player player,
            Material material
    ) {
        OrdersViewState.create(player).selected(material);
    }

    public static void clearSelected(Player player) {
        OrdersViewState.create(player).clearSelected();
    }

    public static void clear(Player player) {
        if (player != null) {
            OrdersViewState.clear(player);
        }
    }

    public static String title(int page) {
        return OrdersGuiItems.cfg(
                "orders.gui.titles.create",
                "Create Order (Page %page%)"
        ).replace(
                "%page%",
                String.valueOf(page)
        );
    }

    private static List<Material> filteredItems(
            Player player
    ) {
        OrdersViewState.CreateState state =
                OrdersViewState.create(player);
        List<Material> materials = new ArrayList<>();
        String query = state.query();

        for (Material material : Material.values()) {
            if (material == Material.AIR
                    || !material.isItem()
                    || !state.filter().matches(material)) {
                continue;
            }

            if (!query.isBlank()
                    && !material.name()
                    .toLowerCase(Locale.ROOT)
                    .contains(query)) {
                continue;
            }

            materials.add(material);
        }

        materials.sort(
                (left, right) -> left.name()
                        .compareToIgnoreCase(right.name())
        );
        return List.copyOf(materials);
    }

    private static int maximumPage(int size) {
        return Math.max(
                1,
                (int) Math.ceil(
                        size / (double) ITEMS_PER_PAGE
                )
        );
    }

    private static ItemStack filterItem(
            OrdersViewState.CreateFilter active
    ) {
        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbCurrent: &#ff88ff"
                        + active.label()
        );
        lore.add("");

        for (OrdersViewState.CreateFilter mode
                : OrdersViewState.CreateFilter.values()) {
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
                Material.HOPPER,
                "&dFilter",
                lore
        );
    }

    private static ItemStack selectedItem(
            OrdersViewState.CreateState state,
            OrderService service
    ) {
        Material selected = state.selected();

        if (selected == null) {
            return OrdersGuiItems.item(
                    Material.PAPER,
                    "&dCreate Order",
                    "&#bbbbbbStep 1: Choose an item",
                    "&#bbbbbbStep 2: Enter the amount",
                    "&#bbbbbbStep 3: Enter the total pay",
                    "",
                    "&#bbbbbbExample: &#ff88ff64 logs for $100k"
            );
        }

        return OrdersGuiItems.item(
                selected,
                "&dSelected: &#ff88ff"
                        + service.pretty(selected),
                "&#bbbbbbClick to continue",
                "",
                "&#bbbbbbYou will enter the amount",
                "&#bbbbbband total escrow in chat"
        );
    }

    private static ItemStack searchItem(
            OrdersViewState.CreateState state
    ) {
        if (!state.hasQuery()) {
            return OrdersGuiItems.item(
                    Material.OAK_SIGN,
                    "&dSearch",
                    "&#bbbbbbClick to search item names"
            );
        }

        return OrdersGuiItems.item(
                Material.OAK_SIGN,
                "&dSearch",
                "&#bbbbbbCurrent: &#ff88ff"
                        + state.query().replace('_', ' '),
                "",
                "&#bbbbbbLeft-click to search again",
                "&#bbbbbbRight-click to clear search"
        );
    }
}
