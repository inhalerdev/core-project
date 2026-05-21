package net.mineacle.core.orders.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class OrderCreateGui {

    public static final int SIZE = 54;
    public static final int ITEMS_PER_PAGE = 45;

    public static final int PREV_SLOT = 45;
    public static final int FILTER_SLOT = 47;
    public static final int SELECTED_SLOT = 49;
    public static final int SEARCH_SLOT = 50;
    public static final int NEXT_SLOT = 53;

    private static final String TITLE_PREFIX = "Choose Item (Page ";

    private static final Map<UUID, Integer> PAGES = new HashMap<>();
    private static final Map<UUID, FilterMode> FILTERS = new HashMap<>();
    private static final Map<UUID, String> SEARCHES = new HashMap<>();
    private static final Map<UUID, Material> SELECTED = new HashMap<>();

    private OrderCreateGui() {
    }

    public static void open(Player player, OrderService service) {
        int page = Math.max(1, PAGES.getOrDefault(player.getUniqueId(), 1));
        List<Material> items = filteredItems(player);
        int maxPage = Math.max(1, (int) Math.ceil(items.size() / (double) ITEMS_PER_PAGE));

        if (page > maxPage) {
            page = maxPage;
            PAGES.put(player.getUniqueId(), page);
        }

        Inventory inventory = Bukkit.createInventory(null, SIZE, title(page));

        int start = (page - 1) * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, items.size());

        for (int index = start; index < end; index++) {
            Material material = items.get(index);

            inventory.setItem(index - start, OrdersMainGui.item(
                    material,
                    "&d" + service.pretty(material),
                    List.of(
                            "&#bbbbbbChoose this item for your order",
                            "",
                            "&#bbbbbbPlayers will deliver this item",
                            "&#bbbbbband receive the pay you set",
                            "",
                            "&#ff88ffClick to choose"
                    )
            ));
        }

        if (items.isEmpty()) {
            inventory.setItem(22, OrdersMainGui.item(
                    Material.BARRIER,
                    "&cNo Items Found",
                    List.of(
                            "&#bbbbbbTry a different search",
                            "&#bbbbbbor clear the filter"
                    )
            ));
        }

        if (page > 1) {
            inventory.setItem(PREV_SLOT, OrdersMainGui.item(
                    Material.ARROW,
                    "&dBack",
                    List.of("&#bbbbbbPrevious page")
            ));
        }

        inventory.setItem(FILTER_SLOT, filterItem(player));

        Material selected = selected(player);

        if (selected == null) {
            inventory.setItem(SELECTED_SLOT, OrdersMainGui.item(
                    Material.PAPER,
                    "&dCreate Order",
                    List.of(
                            "&#bbbbbbStep 1: Choose an item",
                            "&#bbbbbbStep 2: Type how many you want",
                            "&#bbbbbbStep 3: Type total pay",
                            "",
                            "&#bbbbbbExample:",
                            "&#ff88ff64 logs for $100k"
                    )
            ));
        } else {
            inventory.setItem(SELECTED_SLOT, OrdersMainGui.item(
                    selected,
                    "&#ff88ffSelected: &d" + service.pretty(selected),
                    List.of(
                            "&#bbbbbbNow type how many you want",
                            "",
                            "&#bbbbbbExamples:",
                            "&#ff88ff64",
                            "&#ff88ff2304",
                            "",
                            "&#ff88ffClick to continue"
                    )
            ));
        }

        inventory.setItem(SEARCH_SLOT, OrdersMainGui.item(
                Material.OAK_SIGN,
                "&dSearch",
                List.of(
                        "&#bbbbbbSearch item names",
                        "",
                        "&#bbbbbbType clear to reset"
                )
        ));

        if (page < maxPage) {
            inventory.setItem(NEXT_SLOT, OrdersMainGui.item(
                    Material.ARROW,
                    "&dNext",
                    List.of("&#bbbbbbNext page")
            ));
        }

        player.openInventory(inventory);
    }

    public static String title(int page) {
        return TITLE_PREFIX + page + ")";
    }

    public static boolean isTitle(String title) {
        return title != null && title.startsWith(TITLE_PREFIX);
    }

    public static int page(Player player) {
        return Math.max(1, PAGES.getOrDefault(player.getUniqueId(), 1));
    }

    public static void nextPage(Player player) {
        PAGES.put(player.getUniqueId(), page(player) + 1);
    }

    public static void previousPage(Player player) {
        PAGES.put(player.getUniqueId(), Math.max(1, page(player) - 1));
    }

    public static void cycleFilter(Player player) {
        FilterMode current = FILTERS.getOrDefault(player.getUniqueId(), FilterMode.Blocks);
        FILTERS.put(player.getUniqueId(), current.next());
        PAGES.put(player.getUniqueId(), 1);
    }

    public static void setSearch(Player player, String query) {
        if (query == null || query.isBlank() || query.equalsIgnoreCase("clear")
                || query.equalsIgnoreCase("cancel") || query.equalsIgnoreCase("cancelled")) {
            SEARCHES.remove(player.getUniqueId());
        } else {
            SEARCHES.put(player.getUniqueId(), query.toLowerCase(Locale.ROOT));
        }

        PAGES.put(player.getUniqueId(), 1);
    }

    public static List<Material> pageItems(Player player) {
        int page = page(player);
        List<Material> materials = filteredItems(player);
        int start = (page - 1) * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, materials.size());

        if (start >= materials.size()) {
            return List.of();
        }

        return materials.subList(start, end);
    }

    public static Material selected(Player player) {
        return SELECTED.get(player.getUniqueId());
    }

    public static void select(Player player, Material material) {
        SELECTED.put(player.getUniqueId(), material);
    }

    public static void clear(Player player) {
        SELECTED.remove(player.getUniqueId());
        PAGES.remove(player.getUniqueId());
        FILTERS.remove(player.getUniqueId());
        SEARCHES.remove(player.getUniqueId());
    }

    private static List<Material> filteredItems(Player player) {
        FilterMode filter = FILTERS.getOrDefault(player.getUniqueId(), FilterMode.Blocks);
        String search = SEARCHES.get(player.getUniqueId());

        List<Material> materials = new ArrayList<>();

        for (Material material : Material.values()) {
            if (!material.isItem()) {
                continue;
            }

            if (material == Material.AIR) {
                continue;
            }

            if (!filter.matches(material)) {
                continue;
            }

            if (search != null && !material.name().toLowerCase(Locale.ROOT).contains(search)) {
                continue;
            }

            materials.add(material);
        }

        materials.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return materials;
    }

    private static org.bukkit.inventory.ItemStack filterItem(Player player) {
        FilterMode active = FILTERS.getOrDefault(player.getUniqueId(), FilterMode.Blocks);
        List<String> lore = new ArrayList<>();

        lore.add("&#bbbbbbClick to filter");
        lore.add("");

        for (FilterMode mode : FilterMode.values()) {
            lore.add((mode == active ? "&#ff88ff" : "&#bbbbbb") + display(mode.name()));
        }

        return OrdersMainGui.item(Material.HOPPER, "&dFilter", lore);
    }

    private static String display(String raw) {
        StringBuilder builder = new StringBuilder();

        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);

            if (index > 0 && Character.isUpperCase(character)) {
                builder.append(' ');
            }

            builder.append(character);
        }

        return builder.toString();
    }

    private enum FilterMode {
        Blocks,
        Logs,
        Ores,
        Farming,
        MobDrops,
        Food,
        Tools,
        Combat,
        AllItems;

        private FilterMode next() {
            FilterMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        private boolean matches(Material material) {
            String name = material.name();

            return switch (this) {
                case Blocks -> material.isBlock();
                case Logs -> name.endsWith("_LOG") || name.endsWith("_WOOD")
                        || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
                case Ores -> name.contains("ORE") || name.contains("RAW_")
                        || name.contains("INGOT") || name.contains("NUGGET")
                        || name.contains("DIAMOND") || name.contains("EMERALD")
                        || name.contains("LAPIS") || name.contains("REDSTONE");
                case Farming -> Tag.ITEMS_WOOL.isTagged(material)
                        || name.contains("WHEAT") || name.contains("CARROT")
                        || name.contains("POTATO") || name.contains("BEETROOT")
                        || name.contains("PUMPKIN") || name.contains("MELON")
                        || name.contains("SEEDS") || name.contains("SUGAR_CANE")
                        || name.contains("CACTUS") || name.contains("BAMBOO");
                case MobDrops -> name.contains("BONE") || name.contains("ROTTEN")
                        || name.contains("STRING") || name.contains("SPIDER")
                        || name.contains("GUNPOWDER") || name.contains("PEARL")
                        || name.contains("BLAZE") || name.contains("GHAST")
                        || name.contains("LEATHER") || name.contains("FEATHER")
                        || name.contains("SLIME") || name.contains("MAGMA");
                case Food -> material.isEdible();
                case Tools -> name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                        || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                        || name.equals("SHEARS") || name.equals("FISHING_ROD");
                case Combat -> name.endsWith("_SWORD") || name.endsWith("_HELMET")
                        || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                        || name.endsWith("_BOOTS") || name.equals("BOW")
                        || name.equals("CROSSBOW") || name.equals("SHIELD")
                        || name.equals("TRIDENT");
                case AllItems -> true;
            };
        }
    }
}
