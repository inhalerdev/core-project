package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.sell.model.SellHistoryEntry;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class SellHistoryGui {

    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int SORT_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private static final String TITLE_PREFIX =
            "Sell History (Page ";
    private static final Map<Player, SortMode> SORTS =
            new WeakHashMap<>();

    private SellHistoryGui() {
    }

    public static void open(
            @SuppressWarnings("unused") Core core,
            Player player,
            SellService sellService,
            int page
    ) {
        if (player == null
                || !player.isOnline()) {
            return;
        }

        int safeRequestedPage =
                Math.max(
                        0,
                        page
                );
        Holder loadingHolder =
                new Holder(
                        safeRequestedPage
                );
        Inventory loading =
                Bukkit.createInventory(
                        loadingHolder,
                        SIZE,
                        GuiText.title(
                                title(
                                        safeRequestedPage
                                )
                        )
                );
        loadingHolder.inventory =
                loading;
        loading.setItem(
                22,
                item(
                        Material.CLOCK,
                        "&#B078FFLoading Sell History",
                        List.of(
                                "&#bbbbbbFetching your Sell history"
                        )
                )
        );
        player.openInventory(
                loading
        );

        sellService.loadHistoryAsync(
                player.getUniqueId(),
                entries -> {
                    if (!player.isOnline()
                            || player.getOpenInventory()
                            .getTopInventory()
                            != loading) {
                        return;
                    }

                    openLoaded(
                            player,
                            sellService,
                            safeRequestedPage,
                            entries
                    );
                }
        );
    }

    private static void openLoaded(
            Player player,
            SellService sellService,
            int requestedPage,
            List<SellHistoryEntry> loadedEntries
    ) {
        List<SellHistoryEntry> entries =
                sortedEntries(
                        player,
                        sellService,
                        loadedEntries
                );
        int maximumPage =
                Math.max(
                        0,
                        (entries.size() - 1)
                                / ENTRIES_PER_PAGE
                );
        int safePage =
                Math.clamp(
                        requestedPage,
                        0,
                        maximumPage
                );

        Holder holder =
                new Holder(
                        safePage
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                title(safePage)
                        )
                );
        holder.inventory =
                inventory;

        if (entries.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.CHEST,
                            "&#B078FFNo Sell History",
                            List.of(
                                    "&#bbbbbbSell items with &#D0AFFF/sell",
                                    "&#bbbbbbYour sold items will appear here"
                            )
                    )
            );
        } else {
            int start =
                    safePage
                            * ENTRIES_PER_PAGE;
            int end =
                    Math.min(
                            entries.size(),
                            start
                                    + ENTRIES_PER_PAGE
                    );

            for (int index = start;
                 index < end;
                 index++) {
                inventory.setItem(
                        index - start,
                        historyItem(
                                sellService,
                                entries.get(index)
                        )
                );
            }
        }

        if (safePage > 0) {
            inventory.setItem(
                    PREVIOUS_SLOT,
                    navigationItem(
                            true,
                            safePage
                    )
            );
        }

        inventory.setItem(
                SORT_SLOT,
                sortItem(
                        currentSort(player)
                )
        );

        if (safePage < maximumPage) {
            inventory.setItem(
                    NEXT_SLOT,
                    navigationItem(
                            false,
                            safePage + 2
                    )
            );
        }

        player.openInventory(
                inventory
        );
    }

    public static boolean isInventory(
            Inventory inventory
    ) {
        return inventory != null
                && inventory.getHolder(false)
                instanceof Holder;
    }

    public static int currentPage(
            Player player
    ) {
        if (player == null) {
            return 0;
        }

        Inventory top =
                player.getOpenInventory()
                        .getTopInventory();

        if (top.getHolder(false)
                instanceof Holder holder) {
            return holder.page;
        }

        return 0;
    }

    public static void cycleSort(
            Player player,
            boolean previous
    ) {
        if (player == null) {
            return;
        }

        SortMode current =
                currentSort(player);

        SORTS.put(
                player,
                previous
                        ? current.previous()
                        : current.next()
        );
    }

    public static boolean isDisabledNavigation(
            ItemStack item
    ) {
        return item == null
                || item.getType().isAir();
    }

    private static SortMode currentSort(
            Player player
    ) {
        return SORTS.getOrDefault(
                player,
                SortMode.RECENTLY_SOLD
        );
    }

    private static List<SellHistoryEntry>
    sortedEntries(
            Player player,
            SellService sellService,
            List<SellHistoryEntry> loaded
    ) {
        List<SellHistoryEntry> entries =
                new ArrayList<>(
                        loaded == null
                                ? List.of()
                                : loaded
                );
        SortMode sort =
                currentSort(player);

        entries.sort(
                switch (sort) {
                    case AMOUNT ->
                            Comparator
                                    .comparingLong(
                                            SellHistoryEntry
                                                    ::amount
                                    )
                                    .reversed();
                    case NAME_A_Z ->
                            Comparator.comparing(
                                    entry ->
                                            sellService.pretty(
                                                    entry.material()
                                            ),
                                    String.CASE_INSENSITIVE_ORDER
                            );
                    case TOTAL_PRICE ->
                            Comparator
                                    .comparingLong(
                                            SellHistoryEntry
                                                    ::totalCents
                                    )
                                    .reversed();
                    case RECENTLY_SOLD ->
                            Comparator
                                    .comparingLong(
                                            SellHistoryEntry
                                                    ::lastSoldMillis
                                    )
                                    .reversed();
                }
        );

        return entries;
    }

    private static ItemStack historyItem(
            SellService sellService,
            SellHistoryEntry entry
    ) {
        return item(
                entry.material(),
                "&#bbbbbb"
                        + sellService.pretty(
                        entry.material()
                ),
                List.of(
                        "&#bbbbbbTotal Price: &#11fc7b"
                                + sellService.format(
                                entry.totalCents()
                        ),
                        "&#bbbbbbTotal Amount: &#D0AFFF"
                                + MoneyFormatter.compact(
                                entry.amount()
                        ),
                        "&#bbbbbbCategory: &#D0AFFF"
                                + sellService.categoryDisplay(
                                entry.material()
                        )
                )
        );
    }

    private static ItemStack navigationItem(
            boolean previous,
            int displayPage
    ) {
        return item(
                Material.ARROW,
                previous
                        ? "&#B078FFPrevious Page"
                        : "&#B078FFNext Page",
                List.of(
                        "&#bbbbbbPage &#D0AFFF"
                                + displayPage
                )
        );
    }

    private static ItemStack sortItem(
            SortMode current
    ) {
        List<String> lore =
                new ArrayList<>();

        lore.add(
                "&#bbbbbbCurrent: &#D0AFFF"
                        + current.displayName
        );
        lore.add("");

        for (SortMode mode
                : SortMode.values()) {
            lore.add(
                    (mode == current
                            ? "&#D0AFFF"
                            : "&#bbbbbb")
                            + mode.displayName
            );
        }

        lore.add("");
        lore.add(
                "&#bbbbbbLeft-click: Next"
        );
        lore.add(
                "&#bbbbbbRight-click: Previous"
        );

        return item(
                Material.ANVIL,
                "&#B078FFSort",
                lore
        );
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore
    ) {
        ItemStack item =
                new ItemStack(material);
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        GuiText.apply(
                meta,
                name,
                lore
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);
        return item;
    }

    private static String title(
            int page
    ) {
        return TITLE_PREFIX
                + (page + 1)
                + ")";
    }

    private static final class Holder
            implements InventoryHolder {

        private final int page;
        private Inventory inventory;

        private Holder(
                int page
        ) {
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private enum SortMode {
        RECENTLY_SOLD(
                "Recently Sold"
        ),
        AMOUNT(
                "Amount"
        ),
        NAME_A_Z(
                "Name A-Z"
        ),
        TOTAL_PRICE(
                "Total Price"
        );

        private final String displayName;

        SortMode(
                String displayName
        ) {
            this.displayName =
                    displayName;
        }

        private SortMode next() {
            SortMode[] modes =
                    values();

            return modes[
                    (ordinal() + 1)
                            % modes.length
                    ];
        }

        private SortMode previous() {
            SortMode[] modes =
                    values();

            return modes[
                    (ordinal() - 1
                            + modes.length)
                            % modes.length
                    ];
        }
    }
}
