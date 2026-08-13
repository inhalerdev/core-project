package net.mineacle.core.sell.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.text.TextColor;
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
import java.util.WeakHashMap;
import java.util.List;
import java.util.Map;

public final class SellHistoryGui {

    public static final String TITLE_PREFIX =
            "Sell History (Page ";
    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;

    public static final int PREVIOUS_SLOT = 45;
    public static final int SORT_SLOT = 49;
    public static final int NEXT_SLOT = 53;

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
                Math.max(0, page);

        Holder loadingHolder =
                new Holder(safeRequestedPage);
        Inventory loading =
                Bukkit.createInventory(
                        loadingHolder,
                        SIZE,
                        component(
                                title(safeRequestedPage)
                        )
                );
        loadingHolder.inventory = loading;
        loading.setItem(
                22,
                item(
                        Material.CLOCK,
                        "&dLoading Sell History",
                        List.of(
                                "&#bbbbbbFetching your Sell history"
                        )
                )
        );
        player.openInventory(loading);

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
        int totalPages =
                Math.max(
                        1,
                        (int) Math.ceil(
                                entries.size()
                                        / (double)
                                        ENTRIES_PER_PAGE
                        )
                );
        int safePage =
                Math.clamp(
                        requestedPage,
                        0,
                        totalPages - 1
                );

        Holder holder =
                new Holder(safePage);
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        component(
                                title(safePage)
                        )
                );
        holder.inventory = inventory;

        if (entries.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.CHEST,
                            "&dNo Sell History",
                            List.of(
                                    "&#bbbbbbSell items with &#B078FF/sell",
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
                SellHistoryEntry entry =
                        entries.get(index);

                inventory.setItem(
                        index - start,
                        historyItem(
                                sellService,
                                entry
                        )
                );
            }
        }

        if (safePage > 0) {
            inventory.setItem(
                    PREVIOUS_SLOT,
                    item(
                            Material.ARROW,
                            "&dBack",
                            List.of(
                                    "&#bbbbbbClick to go to the previous page"
                            )
                    )
            );
        }

        inventory.setItem(
                SORT_SLOT,
                sortItem(
                        currentSort(player)
                )
        );

        if (safePage
                < totalPages - 1) {
            inventory.setItem(
                    NEXT_SLOT,
                    item(
                            Material.ARROW,
                            "&dNext",
                            List.of(
                                    "&#bbbbbbClick to go to the next page"
                            )
                    )
            );
        }

        player.openInventory(inventory);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isTitle(
            String strippedTitle
    ) {
        return strippedTitle != null
                && strippedTitle.startsWith(
                TITLE_PREFIX
        );
    }

    public static int currentPage(
            Player player
    ) {
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
            Player player
    ) {
        SORTS.put(
                player,
                currentSort(player).next()
        );
    }

    public static SortMode currentSort(
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
                "&d"
                        + sellService.pretty(
                        entry.material()
                ),
                List.of(
                        "&#bbbbbbTotal Price: &#11fc7b"
                                + sellService.format(
                                entry.totalCents()
                        ),
                        "&#bbbbbbTotal Amount: &#B078FF"
                                + MoneyFormatter.compact(
                                entry.amount()
                        ),
                        "&#bbbbbbCategory: &#B078FF"
                                + sellService
                                .categoryDisplay(
                                        entry.material()
                                )
                )
        );
    }

    private static ItemStack sortItem(
            SortMode current
    ) {
        List<String> lore =
                new ArrayList<>();
        lore.add(
                "&#bbbbbbClick to sort"
        );
        lore.add("");

        for (SortMode mode
                : SortMode.values()) {
            lore.add(
                    (mode == current
                            ? "&#B078FF"
                            : "&#bbbbbb")
                            + mode.displayName()
            );
        }

        return item(
                Material.ANVIL,
                "&dSell History",
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

        meta.displayName(
                component(name)
        );
        meta.lore(
                lore.stream()
                        .map(
                                SellHistoryGui::component
                        )
                        .toList()
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


    private static Component component(
            String text
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(text)
                );
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

    public enum SortMode {
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

        public String displayName() {
            return displayName;
        }

        public SortMode next() {
            SortMode[] values =
                    values();

            return values[
                    (ordinal() + 1)
                            % values.length
                    ];
        }
    }
}
