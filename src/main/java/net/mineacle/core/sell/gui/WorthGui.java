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
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class WorthGui {

    public static final String TITLE_PREFIX = "Item Prices (Page ";
    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;

    public static final int PREVIOUS_SLOT = 45;
    public static final int SORT_SLOT = 49;
    public static final int FILTER_SLOT = 50;
    public static final int REFRESH_SLOT = 51;
    public static final int NEXT_SLOT = 53;

    public static final String META_PAGE = "mineacle_worth_page";

    private static final Map<UUID, SortMode> SORTS = new HashMap<>();
    private static final Map<UUID, FilterMode> FILTERS = new HashMap<>();

    private WorthGui() {
    }

    public static void open(Core core, Player player, SellService sellService, int page) {
        List<Material> materials = materials(player, sellService);
        int totalPages = Math.max(1, (int) Math.ceil(materials.size() / (double) ENTRIES_PER_PAGE));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inventory = Bukkit.createInventory(null, SIZE, TITLE_PREFIX + (safePage + 1) + ")");
        player.setMetadata(META_PAGE, new FixedMetadataValue(core, safePage));

        int start = safePage * ENTRIES_PER_PAGE;
        int end = Math.min(materials.size(), start + ENTRIES_PER_PAGE);

        for (int index = start; index < end; index++) {
            Material material = materials.get(index);
            inventory.setItem(index - start, priceItem(player, sellService, material));
        }

        if (safePage > 0) {
            inventory.setItem(PREVIOUS_SLOT, item(
                    Material.ARROW,
                    "&dBack",
                    List.of("&#bbbbbbClick to go to the previous page")
            ));
        }

        inventory.setItem(SORT_SLOT, sortItem(currentSort(player)));
        inventory.setItem(FILTER_SLOT, filterItem(currentFilter(player)));

        inventory.setItem(REFRESH_SLOT, item(
                Material.PAPER,
                "&dItem Prices",
                List.of("&#bbbbbbClick to refresh")
        ));

        if (safePage < totalPages - 1) {
            inventory.setItem(NEXT_SLOT, item(
                    Material.ARROW,
                    "&dNext",
                    List.of("&#bbbbbbClick to go to the next page")
            ));
        }

        player.openInventory(inventory);
    }

    public static boolean isTitle(String strippedTitle) {
        return strippedTitle != null && strippedTitle.startsWith(TITLE_PREFIX);
    }

    public static int currentPage(Player player) {
        if (!player.hasMetadata(META_PAGE)) {
            return 0;
        }

        return player.getMetadata(META_PAGE).get(0).asInt();
    }

    public static SortMode currentSort(Player player) {
        return SORTS.getOrDefault(player.getUniqueId(), SortMode.HIGHEST_PRICE);
    }

    public static FilterMode currentFilter(Player player) {
        return FILTERS.getOrDefault(player.getUniqueId(), FilterMode.ALL);
    }

    public static void cycleSort(Player player) {
        SORTS.put(player.getUniqueId(), currentSort(player).next());
    }

    public static void cycleFilter(Player player) {
        FILTERS.put(player.getUniqueId(), currentFilter(player).next());
    }

    private static List<Material> materials(Player player, SellService sellService) {
        FilterMode filter = currentFilter(player);
        SortMode sort = currentSort(player);
        List<Material> materials = new ArrayList<>();

        for (Material material : Material.values()) {
            if (!material.isItem()) {
                continue;
            }

            if (material == Material.AIR) {
                continue;
            }

            if (!sellService.canSell(new ItemStack(material))) {
                continue;
            }

            if (!filter.matches(material)) {
                continue;
            }

            materials.add(material);
        }

        materials.sort(switch (sort) {
            case HIGHEST_PRICE -> Comparator
                    .comparingLong((Material material) -> sellService.unitWorthCents(player, material))
                    .reversed()
                    .thenComparing(material -> sellService.pretty(material), String.CASE_INSENSITIVE_ORDER);
            case LOWEST_PRICE -> Comparator
                    .comparingLong((Material material) -> sellService.unitWorthCents(player, material))
                    .thenComparing(material -> sellService.pretty(material), String.CASE_INSENSITIVE_ORDER);
            case BY_NAME -> Comparator.comparing(material -> sellService.pretty(material), String.CASE_INSENSITIVE_ORDER);
        });

        return materials;
    }

    private static ItemStack priceItem(Player player, SellService sellService, Material material) {
        long worth = sellService.unitWorthCents(player, material);

        return item(
                material,
                "&d" + sellService.pretty(material),
                List.of(
                        "&#bbbbbbPrice: &a" + sellService.format(worth),
                        "&#bbbbbbCategory: &#ff6fff" + categoryDisplay(material)
                )
        );
    }

    private static ItemStack sortItem(SortMode current) {
        List<String> lore = new ArrayList<>();
        lore.add("&#bbbbbbClick to sort");
        lore.add("");

        for (SortMode mode : SortMode.values()) {
            lore.add((mode == current ? "&#ff6fff" : "&#bbbbbb") + mode.displayName());
        }

        return item(Material.ANVIL, "&dSort", lore);
    }

    private static ItemStack filterItem(FilterMode current) {
        List<String> lore = new ArrayList<>();

        for (FilterMode mode : FilterMode.values()) {
            lore.add((mode == current ? "&#ff6fff" : "&#bbbbbb") + mode.displayName());
        }

        return item(Material.HOPPER, "&dFilter", lore);
    }

    private static String categoryDisplay(Material material) {
        for (FilterMode mode : FilterMode.values()) {
            if (mode == FilterMode.ALL) {
                continue;
            }

            if (mode.matches(material)) {
                return mode.displayName();
            }
        }

        return "Other";
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
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

    public enum SortMode {
        HIGHEST_PRICE("Highest Price"),
        LOWEST_PRICE("Lowest Price"),
        BY_NAME("By Name");

        private final String displayName;

        SortMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum FilterMode {
        ALL("All"),
        BLOCKS("Blocks"),
        TOOLS("Tools"),
        FOOD("Food"),
        COMBAT("Combat"),
        POTIONS("Potions"),
        BOOKS("Books"),
        INGREDIENTS("Ingredients"),
        UTILITIES("Utilities");

        private final String displayName;

        FilterMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public FilterMode next() {
            FilterMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public boolean matches(Material material) {
            if (this == ALL) {
                return true;
            }

            String name = material.name().toUpperCase(Locale.ROOT);

            return switch (this) {
                case BLOCKS -> material.isBlock();
                case TOOLS -> name.endsWith("_PICKAXE")
                        || name.endsWith("_AXE")
                        || name.endsWith("_SHOVEL")
                        || name.endsWith("_HOE")
                        || name.equals("SHEARS")
                        || name.equals("FISHING_ROD")
                        || name.equals("FLINT_AND_STEEL")
                        || name.equals("BRUSH");
                case FOOD -> material.isEdible();
                case COMBAT -> name.endsWith("_SWORD")
                        || name.endsWith("_HELMET")
                        || name.endsWith("_CHESTPLATE")
                        || name.endsWith("_LEGGINGS")
                        || name.endsWith("_BOOTS")
                        || name.equals("BOW")
                        || name.equals("CROSSBOW")
                        || name.equals("TRIDENT")
                        || name.equals("SHIELD")
                        || name.equals("ARROW")
                        || name.equals("SPECTRAL_ARROW")
                        || name.equals("TIPPED_ARROW");
                case POTIONS -> name.contains("POTION")
                        || name.equals("GLASS_BOTTLE")
                        || name.equals("DRAGON_BREATH");
                case BOOKS -> name.contains("BOOK")
                        || name.equals("PAPER")
                        || name.equals("MAP")
                        || name.equals("WRITABLE_BOOK")
                        || name.equals("WRITTEN_BOOK");
                case INGREDIENTS -> name.contains("INGOT")
                        || name.contains("NUGGET")
                        || name.contains("DUST")
                        || name.contains("GEM")
                        || name.contains("CRYSTAL")
                        || name.contains("SHARD")
                        || name.contains("DYE")
                        || name.contains("SEEDS")
                        || name.equals("DIAMOND")
                        || name.equals("EMERALD")
                        || name.equals("COAL")
                        || name.equals("CHARCOAL")
                        || name.equals("QUARTZ")
                        || name.equals("AMETHYST_SHARD")
                        || name.equals("BLAZE_ROD")
                        || name.equals("BLAZE_POWDER")
                        || name.equals("ENDER_PEARL")
                        || name.equals("GHAST_TEAR")
                        || name.equals("MAGMA_CREAM")
                        || name.equals("SLIME_BALL")
                        || name.equals("STRING")
                        || name.equals("BONE");
                case UTILITIES -> name.contains("BUCKET")
                        || name.contains("MINECART")
                        || name.contains("BOAT")
                        || name.contains("SIGN")
                        || name.contains("CHEST")
                        || name.contains("FURNACE")
                        || name.contains("TABLE")
                        || name.contains("ANVIL")
                        || name.contains("BED")
                        || name.contains("DOOR")
                        || name.contains("RAIL")
                        || name.equals("COMPASS")
                        || name.equals("CLOCK")
                        || name.equals("LEAD")
                        || name.equals("NAME_TAG")
                        || name.equals("SADDLE")
                        || name.equals("TOTEM_OF_UNDYING")
                        || name.equals("ELYTRA");
                default -> true;
            };
        }
    }
}
