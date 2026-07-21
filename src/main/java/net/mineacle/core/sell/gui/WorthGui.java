package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
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
            CenteredToolbar.interiorSlots(SIZE, 3);

    public static final int PREVIOUS_SLOT =
            CenteredToolbar.previousSlot(SIZE);
    public static final int SORT_SLOT = TOOLBAR[0];
    public static final int FILTER_SLOT = TOOLBAR[1];
    public static final int REFRESH_SLOT = TOOLBAR[2];
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
            Core core,
            Player player,
            SellService sellService,
            int page
    ) {
        ensureCatalog(sellService);

        List<Material> materials = filtered(
                player,
                sellService
        );
        int maximumPage = Math.max(
                0,
                (materials.size() - 1) / CONTENT_SLOTS
        );
        int safePage = Math.max(
                0,
                Math.min(page, maximumPage)
        );

        PAGES.put(player.getUniqueId(), safePage);

        Holder holder = new Holder(
                player.getUniqueId(),
                safePage
        );
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                title(safePage)
        );
        holder.inventory = inventory;

        int start = safePage * CONTENT_SLOTS;
        int end = Math.min(
                materials.size(),
                start + CONTENT_SLOTS
        );

        for (int index = start; index < end; index++) {
            inventory.setItem(
                    index - start,
                    item(
                            player,
                            sellService,
                            materials.get(index)
                    )
            );
        }

        inventory.setItem(
                PREVIOUS_SLOT,
                navigationItem(
                        true,
                        safePage > 0,
                        Math.max(1, safePage)
                )
        );

        inventory.setItem(
                SORT_SLOT,
                sortToolbar(sort(player))
        );
        inventory.setItem(
                FILTER_SLOT,
                filterToolbar(filter(player))
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

        inventory.setItem(
                NEXT_SLOT,
                navigationItem(
                        false,
                        safePage < maximumPage,
                        Math.min(maximumPage + 1, safePage + 2)
                )
        );

        player.openInventory(inventory);
    }

    public static boolean isInventory(Inventory inventory) {
        return inventory != null
                && inventory.getHolder(false) instanceof Holder;
    }

    public static boolean isTitle(String title) {
        return title != null
                && title.startsWith("Item Prices");
    }

    public static int currentPage(Player player) {
        return PAGES.getOrDefault(
                player.getUniqueId(),
                0
        );
    }

    public static void cycleSort(Player player) {
        SORTS.put(
                player.getUniqueId(),
                sort(player).next()
        );
    }

    public static void cycleFilter(Player player) {
        FILTERS.put(
                player.getUniqueId(),
                filter(player).next()
        );
    }

    public static void clearCatalogCache() {
        CATALOG.clear();
        catalogBuiltAt = 0L;
    }

    public static void clear(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
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

    private static String title(int page) {
        return "Item Prices (Page " + (page + 1) + ")";
    }

    private static void ensureCatalog(
            SellService sellService
    ) {
        long now = System.currentTimeMillis();

        if (!CATALOG.isEmpty()
                && now - catalogBuiltAt
                < CATALOG_TTL_MILLIS) {
            return;
        }

        CATALOG.clear();
        CATALOG.addAll(
                sellService.worthCatalogMaterials()
        );
        catalogBuiltAt = now;
    }

    private static List<Material> filtered(
            Player player,
            SellService sellService
    ) {
        FilterMode filter = filter(player);
        List<Material> result = CATALOG.stream()
                .filter(material ->
                        filter.matches(
                                sellService,
                                material
                        )
                )
                .collect(
                        java.util.stream.Collectors.toCollection(
                                ArrayList::new
                        )
                );

        result.sort(
                sort(player).comparator(
                        player,
                        sellService
                )
        );
        return result;
    }

    private static ItemStack item(
            Player player,
            SellService sellService,
            Material material
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        ItemValuation valuation = sellService.appraise(
                player,
                item
        );
        int stackSize = Math.max(
                1,
                material.getMaxStackSize()
        );
        long stackAppraisal = multiply(
                valuation.appraisedTotalCents(),
                stackSize
        );
        long stackSell = multiply(
                valuation.serverSellCents(),
                stackSize
        );

        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbAppraised Worth: &a"
                        + sellService.format(
                        valuation.appraisedTotalCents()
                )
        );

        if (stackSize > 1) {
            lore.add(
                    "&#bbbbbbStack Appraisal: &a"
                            + sellService.format(
                            stackAppraisal
                    )
            );
        }

        lore.add("");

        if (valuation.sellable()) {
            lore.add(
                    "&#bbbbbbServer Sell: &a"
                            + sellService.format(
                            valuation.serverSellCents()
                    )
            );

            if (stackSize > 1) {
                lore.add(
                        "&#bbbbbbStack Sell: &a"
                                + sellService.format(
                                stackSell
                        )
                );
            }
        } else {
            lore.add("&cPlayer Market Only");
            lore.add(
                    "&#bbbbbbUse /ah or direct player trading"
            );
        }

        if (!valuation.explicitlyPriced()) {
            lore.add("");
            lore.add(
                    "&#bbbbbbEstimated appraisal"
            );
            lore.add(
                    "&#bbbbbbNot accepted by /sell"
            );
        }

        if (Math.abs(
                valuation.combinedMarketMultiplier()
                        - 1.0D
        ) >= 0.01D) {
            lore.add("");
            lore.add(
                    "&#bbbbbbMarket: &#ff88ff"
                            + SellService.formatMultiplier(
                            valuation.combinedMarketMultiplier()
                    )
                            + "x"
            );
        }

        meta.setDisplayName(
                TextColor.color(
                        "&#bbbbbb"
                                + sellService.pretty(material)
                )
        );
        meta.setLore(
                lore.stream()
                        .map(TextColor::color)
                        .toList()
        );
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isDisabledNavigation(ItemStack item) {
        return item != null
                && item.getType()
                == Material.GRAY_STAINED_GLASS_PANE;
    }

    private static ItemStack navigationItem(
            boolean previous,
            boolean enabled,
            int targetPage
    ) {
        if (!enabled) {
            return toolbar(
                    Material.GRAY_STAINED_GLASS_PANE,
                    previous
                            ? "&#bbbbbbPrevious Page"
                            : "&#bbbbbbNext Page",
                    List.of(
                            "&#bbbbbbNo "
                                    + (previous ? "previous" : "next")
                                    + " page"
                    )
            );
        }

        return toolbar(
                Material.ARROW,
                previous
                        ? "&dPrevious Page"
                        : "&dNext Page",
                List.of(
                        "&#bbbbbbPage &#ff88ff"
                                + targetPage
                )
        );
    }

    private static ItemStack sortToolbar(SortMode current) {
        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbCurrent: &#ff88ff"
                        + current.display
        );
        lore.add("");

        for (SortMode mode : SortMode.values()) {
            lore.add(
                    (mode == current
                            ? "&#ff88ff"
                            : "&#bbbbbb")
                            + mode.display
            );
        }

        lore.add("");
        lore.add("&#bbbbbbClick to change sort");
        return toolbar(
                Material.ANVIL,
                "&dSort",
                lore
        );
    }

    private static ItemStack filterToolbar(
            FilterMode current
    ) {
        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbCurrent: &#ff88ff"
                        + current.display
        );
        lore.add("");

        for (FilterMode mode : FilterMode.values()) {
            lore.add(
                    (mode == current
                            ? "&#ff88ff"
                            : "&#bbbbbb")
                            + mode.display
            );
        }

        lore.add("");
        lore.add("&#bbbbbbClick to change filter");
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
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(TextColor.color(name));
        meta.setLore(
                lore.stream()
                        .map(TextColor::color)
                        .toList()
        );
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static SortMode sort(Player player) {
        return SORTS.getOrDefault(
                player.getUniqueId(),
                SortMode.HIGHEST_PRICE
        );
    }

    private static FilterMode filter(Player player) {
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
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private enum SortMode {
        HIGHEST_PRICE("Highest Price"),
        LOWEST_PRICE("Lowest Price"),
        BY_NAME("By Name");

        private final String display;

        SortMode(String display) {
            this.display = display;
        }

        private SortMode next() {
            SortMode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
        }

        private Comparator<Material> comparator(
                Player player,
                SellService sellService
        ) {
            return switch (this) {
                case HIGHEST_PRICE -> Comparator
                        .comparingLong(
                                (Material material) ->
                                        sellService.unitWorthCents(
                                                player,
                                                material
                                        )
                        )
                        .reversed()
                        .thenComparing(
                                sellService::pretty,
                                String.CASE_INSENSITIVE_ORDER
                        );
                case LOWEST_PRICE -> Comparator
                        .comparingLong(
                                (Material material) ->
                                        sellService.unitWorthCents(
                                                player,
                                                material
                                        )
                        )
                        .thenComparing(
                                sellService::pretty,
                                String.CASE_INSENSITIVE_ORDER
                        );
                case BY_NAME -> Comparator.comparing(
                        sellService::pretty,
                        String.CASE_INSENSITIVE_ORDER
                );
            };
        }
    }

    private enum FilterMode {
        ALL("All"),
        FARMING("Farming"),
        ORES("Ores"),
        WOOD("Wood"),
        MOB_DROPS("Mob Drops"),
        NETHER("Nether"),
        END("End"),
        EQUIPMENT("Equipment"),
        CONSUMABLES("Consumables"),
        UTILITY("Utility"),
        RARE("Rare"),
        BLOCKS("Blocks"),
        MISC("Misc");

        private final String display;

        FilterMode(String display) {
            this.display = display;
        }

        private FilterMode next() {
            FilterMode[] modes = values();
            return modes[
                    (ordinal() + 1) % modes.length
                    ];
        }

        private boolean matches(
                SellService sellService,
                Material material
        ) {
            if (this == ALL) {
                return true;
            }

            String category =
                    sellService.category(material);

            return category.equals(
                    name().toLowerCase(Locale.ROOT)
            );
        }
    }

    private static final class Holder
            implements InventoryHolder {

        private final UUID playerId;
        private final int page;
        private Inventory inventory;

        private Holder(UUID playerId, int page) {
            this.playerId = playerId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
