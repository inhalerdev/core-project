package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorthGui {

    public static final int SIZE = 54;
    public static final int PREVIOUS_SLOT = 45;
    public static final int SORT_SLOT = 49;
    public static final int FILTER_SLOT = 50;
    public static final int SEARCH_SLOT = 51;
    public static final int REFRESH_SLOT = 52;
    public static final int NEXT_SLOT = 53;
    public static final String META_SEARCHING = "mineacle_worth_searching";

    private static final int CONTENT_SLOTS = 45;
    private static final long CACHE_TTL_MILLIS = 300000L;

    private static final Map<UUID, Integer> PAGES = new HashMap<>();
    private static final Map<UUID, SortMode> SORTS = new HashMap<>();
    private static final Map<UUID, FilterMode> FILTERS = new HashMap<>();
    private static final Map<UUID, String> SEARCHES = new HashMap<>();

    private static final List<Material> CATALOG = new ArrayList<>();
    private static long catalogBuiltAt = 0L;

    private WorthGui() {
    }

    public static void open(Core core, Player player, SellService sellService, int page) {
        ensureCatalog(sellService);

        List<Material> materials = filtered(player, sellService);
        int maxPage = Math.max(0, (materials.size() - 1) / CONTENT_SLOTS);
        int safePage = Math.max(0, Math.min(page, maxPage));

        PAGES.put(player.getUniqueId(), safePage);

        Inventory inventory = Bukkit.createInventory(null, SIZE, TextColor.color(title(safePage)));

        int start = safePage * CONTENT_SLOTS;
        int end = Math.min(materials.size(), start + CONTENT_SLOTS);

        for (int index = start; index < end; index++) {
            Material material = materials.get(index);
            inventory.setItem(index - start, item(player, sellService, material));
        }

        inventory.setItem(PREVIOUS_SLOT, toolbar(Material.ARROW, "&#bbbbbbPrevious Page", safePage > 0
                ? List.of("&#bbbbbbPage &#ff88ff" + safePage)
                : List.of("&cNo previous page")));

        inventory.setItem(SORT_SLOT, toolbar(Material.ANVIL, "&#bbbbbbSort", List.of(
                "&#bbbbbbCurrent: &#ff88ff" + sort(player).display(),
                "",
                "&#bbbbbbClick to change sort"
        )));

        inventory.setItem(FILTER_SLOT, toolbar(Material.HOPPER, "&#bbbbbbFilter", List.of(
                "&#bbbbbbCurrent: &#ff88ff" + filter(player).display(),
                "",
                "&#bbbbbbClick to change category"
        )));

        String search = SEARCHES.getOrDefault(player.getUniqueId(), "");
        inventory.setItem(SEARCH_SLOT, toolbar(Material.OAK_SIGN, "&#bbbbbbSearch", search.isBlank()
                ? List.of("&#bbbbbbCurrent: &#ff88ffNone", "", "&#bbbbbbClick to search item prices")
                : List.of("&#bbbbbbCurrent: &#ff88ff" + search, "", "&#bbbbbbType &#ff88ffclear &#bbbbbbto clear")));

        inventory.setItem(REFRESH_SLOT, toolbar(Material.PAPER, "&#bbbbbbRefresh", List.of(
                "&#bbbbbbRebuilds the cached worth catalog",
                "&#bbbbbbUse after sell reloads"
        )));

        inventory.setItem(NEXT_SLOT, toolbar(Material.ARROW, "&#bbbbbbNext Page", safePage < maxPage
                ? List.of("&#bbbbbbPage &#ff88ff" + (safePage + 2))
                : List.of("&cNo next page")));

        player.openInventory(inventory);
    }

    public static boolean isTitle(String title) {
        return title != null && title.startsWith("Item Prices");
    }

    public static int currentPage(Player player) {
        return PAGES.getOrDefault(player.getUniqueId(), 0);
    }

    public static void cycleSort(Player player) {
        SORTS.put(player.getUniqueId(), sort(player).next());
    }

    public static void cycleFilter(Player player) {
        FILTERS.put(player.getUniqueId(), filter(player).next());
    }

    public static void setSearch(Player player, String search) {
        if (search == null || search.isBlank()) {
            clearSearch(player);
            return;
        }

        SEARCHES.put(player.getUniqueId(), search.trim());
    }

    public static void clearSearch(Player player) {
        SEARCHES.remove(player.getUniqueId());
    }

    public static void clearCatalogCache() {
        CATALOG.clear();
        catalogBuiltAt = 0L;
    }

    private static String title(int page) {
        return "Item Prices (Page " + (page + 1) + ")";
    }

    private static void ensureCatalog(SellService sellService) {
        long now = System.currentTimeMillis();

        if (!CATALOG.isEmpty() && now - catalogBuiltAt < CACHE_TTL_MILLIS) {
            return;
        }

        CATALOG.clear();

        for (Material material : Material.values()) {
            if (!shouldShowInWorth(material)) {
                continue;
            }

            if (sellService.baseWorthCents(material) <= 0L) {
                continue;
            }

            CATALOG.add(material);
        }

        catalogBuiltAt = now;
    }

    private static List<Material> filtered(Player player, SellService sellService) {
        String search = SEARCHES.getOrDefault(player.getUniqueId(), "").toLowerCase(Locale.ROOT);
        FilterMode filter = filter(player);
        SortMode sort = sort(player);
        List<Material> result = new ArrayList<>();

        for (Material material : CATALOG) {
            if (!search.isBlank()) {
                String itemName = sellService.pretty(material).toLowerCase(Locale.ROOT);
                String rawName = material.name().toLowerCase(Locale.ROOT);

                if (!itemName.contains(search) && !rawName.contains(search.replace(" ", "_"))) {
                    continue;
                }
            }

            if (!filter.matches(sellService, material)) {
                continue;
            }

            result.add(material);
        }

        result.sort(sort.comparator(player, sellService));
        return result;
    }

    private static ItemStack item(Player player, SellService sellService, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        long base = sellService.baseWorthCents(material);
        long unit = sellService.unitWorthCents(player, material);
        double demand = sellService.demandMultiplier(material);

        List<String> lore = new ArrayList<>();
        lore.add("&#bbbbbbValue: &a" + sellService.format(unit));
        lore.add("&#bbbbbbBase: &a" + sellService.format(base));
        lore.add("&#bbbbbbCategory: &#ff88ff" + sellService.categoryDisplay(material));

        if (Math.abs(demand - 1.0D) >= 0.01D) {
            lore.add("&#bbbbbbDemand: &#ff88ff" + SellService.formatMultiplier(demand) + "x");
            lore.add("&#bbbbbbTier: &#ff88ff" + sellService.demandTierDisplay(material));
        }

        long sold = sellService.demandWindowAmount(material);

        if (sold > 0L) {
            lore.add("&#bbbbbbSold This Cycle: &#ff88ff" + sold);
        }

        meta.setDisplayName(TextColor.color("&#bbbbbb" + sellService.pretty(material)));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack toolbar(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(TextColor.color(name));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean shouldShowInWorth(Material material) {
        if (material == null || material == Material.AIR || !material.isItem()) {
            return false;
        }

        String name = material.name();

        if (name.endsWith("_SPAWN_EGG")
                || name.startsWith("LEGACY_")
                || name.startsWith("POTTED_")
                || name.startsWith("INFESTED_")
                || name.contains("COMMAND_BLOCK")) {
            return false;
        }

        return !BLOCKED_WORTH_ITEMS.contains(name);
    }

    private static SortMode sort(Player player) {
        return SORTS.getOrDefault(player.getUniqueId(), SortMode.VALUE_DESC);
    }

    private static FilterMode filter(Player player) {
        return FILTERS.getOrDefault(player.getUniqueId(), FilterMode.ALL);
    }

    private enum SortMode {
        VALUE_DESC("Value High-Low"),
        VALUE_ASC("Value Low-High"),
        NAME("Name"),
        CATEGORY("Category");

        private final String display;

        SortMode(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public Comparator<Material> comparator(Player player, SellService sellService) {
            return switch (this) {
                case VALUE_DESC -> Comparator
                        .comparingLong((Material material) -> sellService.unitWorthCents(player, material))
                        .reversed()
                        .thenComparing(material -> sellService.pretty(material), String.CASE_INSENSITIVE_ORDER);
                case VALUE_ASC -> Comparator
                        .comparingLong((Material material) -> sellService.unitWorthCents(player, material))
                        .thenComparing(material -> sellService.pretty(material), String.CASE_INSENSITIVE_ORDER);
                case NAME -> Comparator.comparing(material -> sellService.pretty(material), String.CASE_INSENSITIVE_ORDER);
                case CATEGORY -> Comparator
                        .comparing((Material material) -> sellService.categoryDisplay(material), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(material -> sellService.pretty(material), String.CASE_INSENSITIVE_ORDER);
            };
        }
    }

    private enum FilterMode {
        ALL("All"),
        FARMING("Farming"),
        ORES("Ores"),
        MOB_DROPS("Mob Drops"),
        NETHER("Nether"),
        END("End"),
        RARE("Rare"),
        BLOCKS("Blocks"),
        MISC("Misc");

        private final String display;

        FilterMode(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public FilterMode next() {
            FilterMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public boolean matches(SellService sellService, Material material) {
            if (this == ALL) {
                return true;
            }

            String category = sellService.category(material);

            return switch (this) {
                case FARMING -> category.equals("farming");
                case ORES -> category.equals("ores");
                case MOB_DROPS -> category.equals("mob_drops");
                case NETHER -> category.equals("nether");
                case END -> category.equals("end");
                case RARE -> category.equals("rare");
                case BLOCKS -> category.equals("blocks");
                case MISC -> category.equals("misc");
                case ALL -> true;
            };
        }
    }

    private static final Set<String> BLOCKED_WORTH_ITEMS = new HashSet<>(Set.of(
            "BARRIER",
            "BEDROCK",
            "COMMAND_BLOCK",
            "CHAIN_COMMAND_BLOCK",
            "REPEATING_COMMAND_BLOCK",
            "COMMAND_BLOCK_MINECART",
            "STRUCTURE_BLOCK",
            "STRUCTURE_VOID",
            "JIGSAW",
            "LIGHT",
            "DEBUG_STICK",
            "KNOWLEDGE_BOOK",
            "SPAWNER",
            "TRIAL_SPAWNER",
            "VAULT",
            "END_PORTAL_FRAME",
            "DRAGON_EGG",
            "FARMLAND",
            "FROGSPAWN",
            "REINFORCED_DEEPSLATE",
            "PLAYER_HEAD",
            "PLAYER_WALL_HEAD",
            "PIGLIN_HEAD",
            "PIGLIN_WALL_HEAD"
    ));
}
