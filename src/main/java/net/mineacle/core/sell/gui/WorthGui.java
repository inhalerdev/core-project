package net.mineacle.core.sell.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiSearchLore;
import net.mineacle.core.common.gui.GuiText;
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

/** Item Prices GUI. Worth is the exact current one-item server payout. */
public final class WorthGui {

    public static final int SIZE = 54;
    private static final int CONTENT_SLOTS = 45;

    private static final int[] TOOLBAR =
            CenteredToolbar.interiorSlotsCenteredOn(SIZE, 4, 2);

    public static final int PREVIOUS_SLOT = CenteredToolbar.previousSlot(SIZE);
    public static final int SORT_SLOT = TOOLBAR[0];
    public static final int FILTER_SLOT = TOOLBAR[1];
    public static final int REFRESH_SLOT = TOOLBAR[2];
    public static final int SEARCH_SLOT = TOOLBAR[3];
    public static final int NEXT_SLOT = CenteredToolbar.nextSlot(SIZE);

    private static final Map<UUID, Integer> PAGES = new HashMap<>();
    private static final Map<UUID, SortMode> SORTS = new HashMap<>();
    private static final Map<UUID, FilterMode> FILTERS = new HashMap<>();
    private static final Map<UUID, String> QUERIES = new HashMap<>();
    private static final List<Material> CATALOG = new ArrayList<>();

    private static List<MarketEntry> MARKET_SNAPSHOT = List.of();
    private static long catalogMaterialGeneration = Long.MIN_VALUE;
    private static long snapshotCatalogGeneration = Long.MIN_VALUE;
    private static long snapshotPriceRevision = Long.MIN_VALUE;

    private WorthGui() {
    }

    public static void open(
            @SuppressWarnings("unused") Core core,
            Player player,
            SellService sellService,
            int page
    ) {
        ensureCatalog(sellService);
        List<MarketEntry> entries = filtered(player, sellService);
        int maximumPage = Math.max(0, (entries.size() - 1) / CONTENT_SLOTS);
        int safePage = Math.clamp(page, 0, maximumPage);
        PAGES.put(player.getUniqueId(), safePage);

        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                GuiText.title("Item Prices (Page " + (safePage + 1) + ")")
        );
        holder.inventory = inventory;

        int start = safePage * CONTENT_SLOTS;
        int end = Math.min(entries.size(), start + CONTENT_SLOTS);
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, item(sellService, entries.get(index)));
        }

        if (entries.isEmpty()) {
            inventory.setItem(
                    22,
                    toolbar(
                            Material.GRAY_DYE,
                            "&#bbbbbbNo Results",
                            query(player).isBlank()
                                    ? List.of("&#bbbbbbNo items match this category")
                                    : List.of(
                                    "&#bbbbbbNo results for &#D0AFFF" + query(player)
                            )
                    )
            );
        }

        if (safePage > 0) {
            inventory.setItem(
                    PREVIOUS_SLOT,
                    navigationItem(true, safePage)
            );
        }

        inventory.setItem(SORT_SLOT, sortToolbar(sort(player)));
        inventory.setItem(FILTER_SLOT, filterToolbar(filter(player)));
        inventory.setItem(
                REFRESH_SLOT,
                toolbar(
                        Material.EMERALD,
                        "&#B078FFRefresh",
                        List.of("&#bbbbbbReload current prices")
                )
        );
        inventory.setItem(SEARCH_SLOT, searchToolbar(query(player)));

        if (safePage < maximumPage) {
            inventory.setItem(
                    NEXT_SLOT,
                    navigationItem(false, safePage + 2)
            );
        }

        player.openInventory(inventory);
    }

    public static boolean isInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder(false) instanceof Holder;
    }

    @SuppressWarnings("unused")
    public static boolean isTitle(String title) {
        return title != null && title.startsWith("Item Prices");
    }

    public static int currentPage(Player player) {
        return PAGES.getOrDefault(player.getUniqueId(), 0);
    }

    public static void cycleSort(Player player) {
        cycleSort(player, false);
    }

    public static void cycleSort(Player player, boolean previous) {
        SortMode current = sort(player);
        SORTS.put(
                player.getUniqueId(),
                previous ? current.previous() : current.next()
        );
    }

    public static void cycleFilter(Player player, boolean previous) {
        FilterMode current = filter(player);
        FILTERS.put(
                player.getUniqueId(),
                previous ? current.previous() : current.next()
        );
    }

    public static String query(Player player) {
        if (player == null) {
            return "";
        }
        return QUERIES.getOrDefault(player.getUniqueId(), "");
    }

    public static void setQuery(Player player, String query) {
        if (player == null) {
            return;
        }
        String normalized = normalizeQuery(query);
        if (normalized.isBlank()) {
            QUERIES.remove(player.getUniqueId());
        } else {
            QUERIES.put(player.getUniqueId(), normalized);
        }
    }

    public static void clearQuery(Player player) {
        if (player != null) {
            QUERIES.remove(player.getUniqueId());
        }
    }

    public static void clearCatalogCache() {
        CATALOG.clear();
        catalogMaterialGeneration = Long.MIN_VALUE;
        invalidateMarketSnapshot();
    }

    public static void clear(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PAGES.remove(playerId);
        SORTS.remove(playerId);
        FILTERS.remove(playerId);
        QUERIES.remove(playerId);
    }

    public static void clearAllState() {
        PAGES.clear();
        SORTS.clear();
        FILTERS.clear();
        QUERIES.clear();
        clearCatalogCache();
    }

    public static boolean isDisabledNavigation(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static void ensureCatalog(SellService sellService) {
        long generation = sellService.catalogGeneration();
        if (!CATALOG.isEmpty() && catalogMaterialGeneration == generation) {
            return;
        }
        CATALOG.clear();
        CATALOG.addAll(sellService.worthCatalogMaterials());
        catalogMaterialGeneration = generation;
        invalidateMarketSnapshot();
    }

    private static void invalidateMarketSnapshot() {
        MARKET_SNAPSHOT = List.of();
        snapshotCatalogGeneration = Long.MIN_VALUE;
        snapshotPriceRevision = Long.MIN_VALUE;
    }

    private static List<MarketEntry> filtered(
            Player player,
            SellService sellService
    ) {
        FilterMode filter = filter(player);
        String query = query(player).toLowerCase(Locale.ROOT);
        List<MarketEntry> result = new ArrayList<>();
        ensureMarketSnapshot(sellService);

        for (MarketEntry entry : MARKET_SNAPSHOT) {
            if (!filter.matches(entry)) {
                continue;
            }
            if (!query.isBlank() && !searchMatches(entry, query)) {
                continue;
            }
            result.add(entry);
        }

        result.sort(sort(player).comparator());
        return result;
    }

    private static boolean searchMatches(MarketEntry entry, String query) {
        return entry.displayName().toLowerCase(Locale.ROOT).contains(query)
                || entry.material().name()
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .contains(query);
    }

    private static void ensureMarketSnapshot(SellService sellService) {
        long catalogGeneration = sellService.catalogGeneration();
        long priceRevision = sellService.marketPriceRevision();

        if (!MARKET_SNAPSHOT.isEmpty()
                && snapshotCatalogGeneration == catalogGeneration
                && snapshotPriceRevision == priceRevision) {
            return;
        }

        List<MarketEntry> rebuilt = new ArrayList<>(CATALOG.size());
        for (Material material : CATALOG) {
            long unitSell = Math.max(
                    0L,
                    sellService.serverUnitSellCents((Player) null, material)
            );
            boolean sellable = unitSell > 0L
                    && sellService.isServerSellableMaterial(material);

            /*
             * /worth is the server liquidation catalog. Do not show technical,
             * blocked, or mechanically unavailable rows as if they had a
             * player-market appraisal. Every visible entry must be a value the
             * server will actually pay right now.
             */
            if (!sellable) {
                continue;
            }

            rebuilt.add(
                    new MarketEntry(
                            material,
                            sellService.pretty(material),
                            sellService.category(material),
                            true,
                            unitSell
                    )
            );
        }

        MARKET_SNAPSHOT = List.copyOf(rebuilt);
        snapshotCatalogGeneration = catalogGeneration;
        snapshotPriceRevision = priceRevision;
    }

    private static ItemStack item(
            SellService sellService,
            MarketEntry entry
    ) {
        ItemStack item = new ItemStack(entry.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbWorth: &#11fc7b"
                        + sellService.format(entry.unitSellCents())
        );

        meta.displayName(uiComponent("&#bbbbbb" + entry.displayName()));
        meta.lore(uiLore(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack navigationItem(boolean previous, int targetPage) {
        return toolbar(
                Material.ARROW,
                previous ? "&#B078FFPrevious Page" : "&#B078FFNext Page",
                List.of("&#bbbbbbPage &#D0AFFF" + targetPage)
        );
    }

    private static ItemStack sortToolbar(SortMode current) {
        List<String> lore = new ArrayList<>();
        lore.add("&#bbbbbbCurrent: &#D0AFFF" + current.display);
        lore.add("");
        for (SortMode mode : SortMode.values()) {
            lore.add(
                    (mode == current ? "&#D0AFFF" : "&#bbbbbb")
                            + mode.display
            );
        }
        lore.add("");
        lore.add("&#bbbbbbLeft-click: Next");
        lore.add("&#bbbbbbRight-click: Previous");
        return toolbar(Material.ANVIL, "&#B078FFSort", lore);
    }

    private static ItemStack filterToolbar(FilterMode current) {
        List<String> lore = new ArrayList<>();
        lore.add("&#bbbbbbCurrent: &#D0AFFF" + current.display);
        lore.add("");
        for (FilterMode mode : FilterMode.values()) {
            lore.add(
                    (mode == current ? "&#D0AFFF" : "&#bbbbbb")
                            + mode.display
            );
        }
        lore.add("");
        lore.add("&#bbbbbbLeft-click: Next");
        lore.add("&#bbbbbbRight-click: Previous");
        return toolbar(Material.HOPPER, "&#B078FFFilter", lore);
    }

    private static ItemStack searchToolbar(String query) {
        return toolbar(
                Material.OAK_SIGN,
                "&#B078FFSearch",
                query == null || query.isBlank()
                        ? GuiSearchLore.inactive("items")
                        : GuiSearchLore.active(query)
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
        meta.displayName(uiComponent(name));
        meta.lore(uiLore(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static Component uiComponent(String input) {
        return GuiText.component(input)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static List<Component> uiLore(List<String> lines) {
        return GuiText.lore(lines).stream()
                .map(component -> component.decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    private static SortMode sort(Player player) {
        return SORTS.getOrDefault(player.getUniqueId(), SortMode.HIGHEST_PRICE);
    }

    private static FilterMode filter(Player player) {
        return FILTERS.getOrDefault(player.getUniqueId(), FilterMode.ALL);
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.trim().replace('_', ' ');
        return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
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

        private SortMode previous() {
            SortMode[] modes = values();
            return modes[(ordinal() - 1 + modes.length) % modes.length];
        }

        private Comparator<MarketEntry> comparator() {
            Comparator<MarketEntry> name = Comparator.comparing(
                    MarketEntry::displayName,
                    String.CASE_INSENSITIVE_ORDER
            );
            Comparator<MarketEntry> availableFirst = Comparator.comparingInt(
                    entry -> entry.sellable() && entry.unitSellCents() > 0L ? 0 : 1
            );
            return switch (this) {
                case HIGHEST_PRICE -> availableFirst
                        .thenComparing(
                                Comparator.comparingLong(MarketEntry::unitSellCents)
                                        .reversed()
                        )
                        .thenComparing(name);
                case LOWEST_PRICE -> availableFirst
                        .thenComparingLong(MarketEntry::unitSellCents)
                        .thenComparing(name);
                case BY_NAME -> name;
            };
        }
    }

    private enum FilterMode {
        ALL("All"),
        BLOCKS("Blocks"),
        RESOURCES("Resources"),
        FARMING("Farming"),
        MOB_DROPS("Mob Drops"),
        GEAR("Gear");

        private final String display;

        FilterMode(String display) {
            this.display = display;
        }

        private FilterMode next() {
            FilterMode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
        }

        private FilterMode previous() {
            FilterMode[] modes = values();
            return modes[(ordinal() - 1 + modes.length) % modes.length];
        }

        private boolean matches(MarketEntry entry) {
            String category = entry.category().toLowerCase(Locale.ROOT);
            return switch (this) {
                case ALL -> true;
                case BLOCKS -> category.equals("blocks");
                case RESOURCES -> category.equals("ores")
                        || category.equals("wood")
                        || category.equals("nether")
                        || category.equals("end");
                case FARMING -> category.equals("farming");
                case MOB_DROPS -> category.equals("mob_drops");
                case GEAR -> category.equals("equipment")
                        || category.equals("consumables")
                        || category.equals("utility")
                        || category.equals("combat")
                        || category.equals("rare")
                        || category.equals("misc");
            };
        }
    }

    private record MarketEntry(
            Material material,
            String displayName,
            String category,
            boolean sellable,
            long unitSellCents
    ) {
    }

    private static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
