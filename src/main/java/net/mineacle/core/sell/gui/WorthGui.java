package net.mineacle.core.sell.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.model.ItemValuation;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class WorthGui {

    public static final int SIZE = 54;

    private static final int[] TOOLBAR =
            CenteredToolbar.interiorSlots(
                    SIZE,
                    3
            );

    public static final int PREVIOUS_SLOT =
            CenteredToolbar.previousSlot(SIZE);
    public static final int SORT_SLOT =
            TOOLBAR[0];
    public static final int FILTER_SLOT =
            TOOLBAR[1];
    public static final int REFRESH_SLOT =
            TOOLBAR[2];
    public static final int NEXT_SLOT =
            CenteredToolbar.nextSlot(SIZE);

    private static final int CONTENT_SLOTS = 45;
    private static final long CATALOG_TTL_MILLIS =
            5L * 60L * 1000L;

    private static final Map<UUID, Integer> PAGES =
            new HashMap<>();
    private static final Map<UUID, SortMode> SORTS =
            new HashMap<>();
    private static final Map<UUID, FilterMode> FILTERS =
            new HashMap<>();

    private static final List<Material> CATALOG =
            new ArrayList<>();
    private static long catalogBuiltAt;

    private WorthGui() {
    }

    public static void open(
            @SuppressWarnings("unused") Core core,
            Player player,
            SellService sellService,
            int page
    ) {
        ensureCatalog(
                sellService
        );

        List<MarketEntry> entries =
                filtered(
                        player,
                        sellService
                );
        int maximumPage =
                Math.max(
                        0,
                        (entries.size() - 1)
                                / CONTENT_SLOTS
                );
        int safePage =
                Math.clamp(
                        page,
                        0,
                        maximumPage
                );

        PAGES.put(
                player.getUniqueId(),
                safePage
        );

        Holder holder =
                new Holder();
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        component(
                                title(safePage)
                        )
                );
        holder.inventory =
                inventory;

        int start =
                safePage
                        * CONTENT_SLOTS;
        int end =
                Math.min(
                        entries.size(),
                        start
                                + CONTENT_SLOTS
                );

        for (int index = start;
             index < end;
             index++) {
            inventory.setItem(
                    index - start,
                    item(
                            sellService,
                            entries.get(index)
                    )
            );
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
                sortToolbar(
                        sort(player)
                )
        );
        inventory.setItem(
                FILTER_SLOT,
                filterToolbar(
                        filter(player)
                )
        );
        inventory.setItem(
                REFRESH_SLOT,
                toolbar(
                        Material.PAPER,
                        "&dRefresh",
                        List.of(
                                "&#bbbbbbRefresh current market prices"
                        )
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

    @SuppressWarnings("unused")
    public static boolean isTitle(
            String title
    ) {
        return title != null
                && title.startsWith(
                        "Item Prices"
                );
    }

    public static int currentPage(
            Player player
    ) {
        return PAGES.getOrDefault(
                player.getUniqueId(),
                0
        );
    }

    public static void cycleSort(
            Player player
    ) {
        SORTS.put(
                player.getUniqueId(),
                sort(player).next()
        );
    }

    public static void cycleFilter(
            Player player
    ) {
        FILTERS.put(
                player.getUniqueId(),
                filter(player).next()
        );
    }

    public static void clearCatalogCache() {
        CATALOG.clear();
        catalogBuiltAt = 0L;
    }

    public static void clear(
            Player player
    ) {
        if (player == null) {
            return;
        }

        UUID playerId =
                player.getUniqueId();

        PAGES.remove(playerId);
        SORTS.remove(playerId);
        FILTERS.remove(playerId);
    }

    public static void clearAllState() {
        PAGES.clear();
        SORTS.clear();
        FILTERS.clear();
        clearCatalogCache();
    }

    public static boolean isDisabledNavigation(
            ItemStack item
    ) {
        return item == null
                || item.getType().isAir();
    }

    private static String title(
            int page
    ) {
        return "Item Prices (Page "
                + (page + 1)
                + ")";
    }

    private static void ensureCatalog(
            SellService sellService
    ) {
        long now =
                System.currentTimeMillis();

        if (!CATALOG.isEmpty()
                && now - catalogBuiltAt
                < CATALOG_TTL_MILLIS) {
            return;
        }

        CATALOG.clear();
        CATALOG.addAll(
                sellService
                        .worthCatalogMaterials()
        );
        catalogBuiltAt = now;
    }

    private static List<MarketEntry> filtered(
            Player player,
            SellService sellService
    ) {
        FilterMode filter =
                filter(player);
        List<MarketEntry> result =
                new ArrayList<>();

        for (Material material
                : CATALOG) {
            MarketEntry entry =
                    snapshot(
                            player,
                            sellService,
                            material
                    );

            if (filter.matches(entry)) {
                result.add(entry);
            }
        }

        result.sort(
                sort(player)
                        .comparator()
        );

        return result;
    }

    private static MarketEntry snapshot(
            Player player,
            SellService sellService,
            Material material
    ) {
        ItemStack raw =
                new ItemStack(material);
        ItemValuation valuation =
                sellService.appraise(
                        player,
                        raw
                );

        int stackSize =
                Math.max(
                        1,
                        material.getMaxStackSize()
                );

        long unitSell =
                Math.max(
                        0L,
                        valuation.serverSellCents()
                );
        long stackSell =
                multiply(
                        unitSell,
                        stackSize
                );

        boolean marketEnabled =
                !sellService
                        .isDemandExcluded(
                                material
                        );
        double multiplier =
                marketEnabled
                        ? sellService
                        .demandMultiplier(
                                material
                        )
                        : 1.0D;
        long supply24h =
                marketEnabled
                        ? sellService
                        .demandWindowAmount(
                                material
                        )
                        : 0L;
        long target =
                marketEnabled
                        ? sellService
                        .marketTargetUnits(
                                material
                        )
                        : 0L;
        double ratio =
                marketEnabled
                        ? sellService
                        .marketSupplyRatio(
                                material
                        )
                        : 1.0D;
        boolean featured =
                marketEnabled
                        && sellService
                        .isActiveDemandItem(
                                material
                        );
        String tier =
                marketEnabled
                        ? sellService
                        .demandTier(
                                material
                        )
                        : "fixed";

        return new MarketEntry(
                material,
                sellService.pretty(material),
                sellService.category(material),
                valuation,
                unitSell,
                stackSell,
                stackSize,
                marketEnabled,
                multiplier,
                supply24h,
                target,
                ratio,
                featured,
                tier
        );
    }

    private static ItemStack item(
            SellService sellService,
            MarketEntry entry
    ) {
        ItemStack item =
                new ItemStack(
                        entry.material()
                );
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<String> lore =
                new ArrayList<>();

        if (entry.valuation()
                .sellable()
                && entry.unitSellCents()
                > 0L) {
            lore.add(
                    "&#bbbbbbPrice: &#11fc7b"
                            + sellService.format(
                            entry.unitSellCents()
                    )
            );

            if (entry.stackSize() > 1) {
                lore.add(
                        "&#bbbbbbStack: &#11fc7b"
                                + sellService.format(
                                entry.stackSellCents()
                        )
                );
            }
        } else {
            lore.add(
                    "&cPlayer Market Only"
            );
            lore.add(
                    "&#bbbbbbUse /ah or direct player trading"
            );
        }

        lore.add("");

        if (entry.marketEnabled()) {
            lore.add(
                    "&#bbbbbbMarket: "
                            + tierColor(
                            entry.tier()
                    )
                            + tierDisplay(
                            entry.tier()
                    )
            );
            lore.add(
                    "&#bbbbbbMultiplier: &#B078FF"
                            + SellService
                            .formatMultiplier(
                                    entry.multiplier()
                            )
                            + "x"
            );
            lore.add(
                    "&#bbbbbb24h Supply: &#B078FF"
                            + compact(
                            entry.supply24h()
                    )
                            + " &#bbbbbb/ &#B078FF"
                            + compact(
                            entry.target()
                    )
            );
            lore.add(
                    "&#bbbbbbTarget Filled: &#B078FF"
                            + percent(
                            entry.ratio()
                    )
            );

            if (entry.featured()) {
                lore.add(
                        "&#B078FFFeatured Demand"
                );
            }
        } else if (entry.valuation()
                .sellable()) {
            lore.add(
                    "&#bbbbbbMarket: &#bbbbbbFixed Price"
            );
        }

        meta.displayName(
                component(
                        "&#bbbbbb"
                                + entry.displayName()
                )
        );
        meta.lore(
                lore.stream()
                        .map(
                                WorthGui::component
                        )
                        .toList()
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);

        return item;
    }

    private static String tierColor(
            String tier
    ) {
        if (tier == null) {
            return "&#bbbbbb";
        }

        return switch (
                tier.toLowerCase(
                        Locale.ROOT
                )
        ) {
            case "featured" ->
                    "&#B078FF";
            case "shortage",
                 "high_demand" ->
                    "&#11fc7b";
            case "oversupplied",
                 "saturated" ->
                    "&c";
            default ->
                    "&#bbbbbb";
        };
    }

    private static String tierDisplay(
            String tier
    ) {
        if (tier == null
                || tier.isBlank()) {
            return "Normal";
        }

        return switch (
                tier.toLowerCase(
                        Locale.ROOT
                )
        ) {
            case "featured" ->
                    "Featured";
            case "shortage" ->
                    "High Demand";
            case "high_demand" ->
                    "High Demand";
            case "oversupplied" ->
                    "Oversupplied";
            case "saturated" ->
                    "Saturated";
            case "fixed" ->
                    "Fixed Price";
            default ->
                    "Normal";
        };
    }

    private static String percent(
            double ratio
    ) {
        if (!Double.isFinite(ratio)
                || ratio < 0.0D) {
            return "0%";
        }

        long rounded =
                Math.round(
                        ratio * 100.0D
                );

        return rounded + "%";
    }

    private static String compact(
            long value
    ) {
        return MoneyFormatter.compact(
                Math.max(
                        0L,
                        value
                )
        );
    }

    private static ItemStack navigationItem(
            boolean previous,
            int targetPage
    ) {
        return toolbar(
                Material.ARROW,
                previous
                        ? "&dPrevious Page"
                        : "&dNext Page",
                List.of(
                        "&#bbbbbbPage &#B078FF"
                                + targetPage
                )
        );
    }

    private static ItemStack sortToolbar(
            SortMode current
    ) {
        List<String> lore =
                new ArrayList<>();

        lore.add(
                "&#bbbbbbCurrent: &#B078FF"
                        + current.display
        );
        lore.add("");

        for (SortMode mode
                : SortMode.values()) {
            lore.add(
                    (mode == current
                            ? "&#B078FF"
                            : "&#bbbbbb")
                            + mode.display
            );
        }

        lore.add("");
        lore.add(
                "&#bbbbbbClick to change sort"
        );

        return toolbar(
                Material.ANVIL,
                "&dSort",
                lore
        );
    }

    private static ItemStack filterToolbar(
            FilterMode current
    ) {
        List<String> lore =
                new ArrayList<>();

        lore.add(
                "&#bbbbbbCurrent: &#B078FF"
                        + current.display
        );
        lore.add("");

        for (FilterMode mode
                : FilterMode.values()) {
            lore.add(
                    (mode == current
                            ? "&#B078FF"
                            : "&#bbbbbb")
                            + mode.display
            );
        }

        lore.add("");
        lore.add(
                "&#bbbbbbClick to change filter"
        );

        return toolbar(
                Material.HOPPER,
                "&dFilter",
                lore
        );
    }

    private static ItemStack toolbar(
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
                                WorthGui::component
                        )
                        .toList()
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);

        return item;
    }

    private static SortMode sort(
            Player player
    ) {
        return SORTS.getOrDefault(
                player.getUniqueId(),
                SortMode.HIGHEST_PRICE
        );
    }

    private static FilterMode filter(
            Player player
    ) {
        return FILTERS.getOrDefault(
                player.getUniqueId(),
                FilterMode.ALL
        );
    }

    private static long multiply(
            long value,
            int multiplier
    ) {
        try {
            return Math.multiplyExact(
                    value,
                    multiplier
            );
        } catch (
                ArithmeticException exception
        ) {
            return Long.MAX_VALUE;
        }
    }

    private enum SortMode {
        HIGHEST_PRICE(
                "Highest Price"
        ),
        LOWEST_PRICE(
                "Lowest Price"
        ),
        HIGHEST_DEMAND(
                "Highest Demand"
        ),
        MOST_OVERSUPPLIED(
                "Most Oversupplied"
        ),
        FEATURED(
                "Featured"
        ),
        BY_NAME(
                "By Name"
        );

        private final String display;

        SortMode(
                String display
        ) {
            this.display =
                    display;
        }

        private SortMode next() {
            SortMode[] modes =
                    values();

            return modes[
                    (ordinal() + 1)
                            % modes.length
                    ];
        }

        private Comparator<MarketEntry>
        comparator() {
            Comparator<MarketEntry> name =
                    Comparator.comparing(
                            MarketEntry::displayName,
                            String.CASE_INSENSITIVE_ORDER
                    );

            return switch (this) {
                case HIGHEST_PRICE ->
                        Comparator
                                .comparingLong(
                                        MarketEntry
                                                ::unitSellCents
                                )
                                .reversed()
                                .thenComparing(name);
                case LOWEST_PRICE ->
                        Comparator
                                .comparingLong(
                                        MarketEntry
                                                ::unitSellCents
                                )
                                .thenComparing(name);
                case HIGHEST_DEMAND ->
                        Comparator
                                .comparing(
                                        MarketEntry
                                                ::marketEnabled
                                )
                                .reversed()
                                .thenComparing(
                                        Comparator
                                                .comparingDouble(
                                                        MarketEntry
                                                                ::multiplier
                                                )
                                                .reversed()
                                )
                                .thenComparingDouble(
                                        MarketEntry::ratio
                                )
                                .thenComparing(name);
                case MOST_OVERSUPPLIED ->
                        Comparator
                                .comparing(
                                        MarketEntry
                                                ::marketEnabled
                                )
                                .reversed()
                                .thenComparing(
                                        Comparator
                                                .comparingDouble(
                                                        MarketEntry
                                                                ::ratio
                                                )
                                                .reversed()
                                )
                                .thenComparing(name);
                case FEATURED ->
                        Comparator
                                .comparing(
                                        MarketEntry
                                                ::featured
                                )
                                .reversed()
                                .thenComparing(
                                        Comparator
                                                .comparingDouble(
                                                        MarketEntry
                                                                ::multiplier
                                                )
                                                .reversed()
                                )
                                .thenComparing(name);
                case BY_NAME ->
                        name;
            };
        }
    }

    private enum FilterMode {
        ALL(
                "All"
        ),
        FEATURED(
                "Featured"
        ),
        HIGH_DEMAND(
                "High Demand"
        ),
        OVERSUPPLIED(
                "Oversupplied"
        ),
        FARMING(
                "Farming"
        ),
        ORES(
                "Ores"
        ),
        WOOD(
                "Wood"
        ),
        MOB_DROPS(
                "Mob Drops"
        ),
        NETHER(
                "Nether"
        ),
        END(
                "End"
        ),
        EQUIPMENT(
                "Equipment"
        ),
        CONSUMABLES(
                "Consumables"
        ),
        UTILITY(
                "Utility"
        ),
        RARE(
                "Rare"
        ),
        BLOCKS(
                "Blocks"
        ),
        MISC(
                "Misc"
        );

        private final String display;

        FilterMode(
                String display
        ) {
            this.display =
                    display;
        }

        private FilterMode next() {
            FilterMode[] modes =
                    values();

            return modes[
                    (ordinal() + 1)
                            % modes.length
                    ];
        }

        private boolean matches(
                MarketEntry entry
        ) {
            return switch (this) {
                case ALL ->
                        true;
                case FEATURED ->
                        entry.featured();
                case HIGH_DEMAND ->
                        entry.marketEnabled()
                                && (entry.tier()
                                .equalsIgnoreCase(
                                        "shortage"
                                )
                                || entry.tier()
                                .equalsIgnoreCase(
                                        "high_demand"
                                )
                                || entry.tier()
                                .equalsIgnoreCase(
                                        "featured"
                                ));
                case OVERSUPPLIED ->
                        entry.marketEnabled()
                                && (entry.tier()
                                .equalsIgnoreCase(
                                        "oversupplied"
                                )
                                || entry.tier()
                                .equalsIgnoreCase(
                                        "saturated"
                                ));
                default ->
                        entry.category()
                                .equals(
                                        name()
                                                .toLowerCase(
                                                        Locale.ROOT
                                                )
                                );
            };
        }
    }

    private record MarketEntry(
            Material material,
            String displayName,
            String category,
            ItemValuation valuation,
            long unitSellCents,
            long stackSellCents,
            int stackSize,
            boolean marketEnabled,
            double multiplier,
            long supply24h,
            long target,
            double ratio,
            boolean featured,
            String tier
    ) {
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

        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
