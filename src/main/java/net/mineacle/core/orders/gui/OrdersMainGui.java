package net.mineacle.core.orders.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class OrdersMainGui {

    public static final int SIZE = 54;
    public static final int ORDERS_PER_PAGE = 45;

    public static final int PREV_SLOT = 45;
    public static final int SORT_SLOT = 47;
    public static final int FILTER_SLOT = 48;
    public static final int REFRESH_SLOT = 49;
    public static final int SEARCH_SLOT = 50;
    public static final int MY_ORDERS_SLOT = 51;
    public static final int NEXT_SLOT = 53;

    private static final Map<UUID, Integer> PAGES = new HashMap<>();
    private static final Map<UUID, SortMode> SORTS = new HashMap<>();
    private static final Map<UUID, FilterMode> FILTERS = new HashMap<>();
    private static final Map<UUID, String> SEARCHES = new HashMap<>();

    private OrdersMainGui() {
    }

    public static void open(Player player, OrderService service) {
        int page = Math.max(1, PAGES.getOrDefault(player.getUniqueId(), 1));
        List<OrderRecord> orders = filteredOrders(player, service);
        int maxPage = Math.max(1, (int) Math.ceil(orders.size() / (double) ORDERS_PER_PAGE));

        if (page > maxPage) {
            page = maxPage;
            PAGES.put(player.getUniqueId(), page);
        }

        Inventory inventory = Bukkit.createInventory(null, SIZE, title(page));

        int start = (page - 1) * ORDERS_PER_PAGE;
        int end = Math.min(start + ORDERS_PER_PAGE, orders.size());

        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, orderItem(service, orders.get(index)));
        }

        if (orders.isEmpty()) {
            inventory.setItem(22, item(
                    Material.BARREL,
                    "&dNo Orders",
                    List.of(
                            "&#bbbbbbNo player orders are open right now",
                            "",
                            "&#bbbbbbOrders are player requests",
                            "&#bbbbbbthat usually pay more than /sell"
                    )
            ));
        }

        if (page > 1) {
            inventory.setItem(PREV_SLOT, item(
                    material("orders.gui.buttons.previous.material", Material.ARROW),
                    cfg("orders.gui.buttons.previous.name", "&dBack"),
                    lore("orders.gui.buttons.previous.lore", List.of("&#bbbbbbPrevious page"))
            ));
        }

        inventory.setItem(SORT_SLOT, sortItem(player));
        inventory.setItem(FILTER_SLOT, filterItem(player));

        inventory.setItem(REFRESH_SLOT, item(
                material("orders.gui.buttons.refresh.material", Material.PAPER),
                cfg("orders.gui.buttons.refresh.name", "&dOrders"),
                lore("orders.gui.buttons.refresh.lore", List.of(
                        "&#bbbbbbClick to refresh",
                        "",
                        "&#bbbbbbPlayers want items",
                        "&#bbbbbbYou deliver and get paid"
                ))
        ));

        inventory.setItem(SEARCH_SLOT, item(
                material("orders.gui.buttons.search.material", Material.OAK_SIGN),
                cfg("orders.gui.buttons.search.name", "&dSearch"),
                lore("orders.gui.buttons.search.lore", List.of("&#bbbbbbClick to search orders"))
        ));

        inventory.setItem(MY_ORDERS_SLOT, item(
                material("orders.gui.buttons.my-orders.material", Material.PLAYER_HEAD),
                cfg("orders.gui.buttons.my-orders.name", "&dMy Orders"),
                lore("orders.gui.buttons.my-orders.lore", List.of(
                        "&#bbbbbbView orders you created",
                        "&#bbbbbbCollect delivered items",
                        "&#bbbbbbor cancel active orders"
                ))
        ));

        if (page < maxPage) {
            inventory.setItem(NEXT_SLOT, item(
                    material("orders.gui.buttons.next.material", Material.ARROW),
                    cfg("orders.gui.buttons.next.name", "&dNext"),
                    lore("orders.gui.buttons.next.lore", List.of("&#bbbbbbNext page"))
            ));
        }

        player.openInventory(inventory);
    }

    public static String title(int page) {
        return TextColor.color(cfg("orders.gui.titles.main", "Orders (Page %page%)")
                .replace("%page%", String.valueOf(page)));
    }

    public static boolean isTitle(String title) {
        if (title == null) {
            return false;
        }

        String rawTitle = cfg("orders.gui.titles.main", "Orders (Page %page%)");
        String strippedPattern = ChatColor.stripColor(TextColor.color(rawTitle));
        String beforePage = strippedPattern.split("%page%", 2)[0];

        return title.startsWith(beforePage);
    }

    public static int page(Player player) {
        return Math.max(1, PAGES.getOrDefault(player.getUniqueId(), 1));
    }

    public static void nextPage(Player player, OrderService service) {
        List<OrderRecord> orders = filteredOrders(player, service);
        int maxPage = Math.max(1, (int) Math.ceil(orders.size() / (double) ORDERS_PER_PAGE));

        PAGES.put(player.getUniqueId(), Math.min(maxPage, page(player) + 1));
    }

    public static void previousPage(Player player) {
        PAGES.put(player.getUniqueId(), Math.max(1, page(player) - 1));
    }

    public static void cycleSort(Player player) {
        SortMode current = SORTS.getOrDefault(player.getUniqueId(), SortMode.BestPay);
        SORTS.put(player.getUniqueId(), current.next());
        PAGES.put(player.getUniqueId(), 1);
    }

    public static void cycleFilter(Player player) {
        FilterMode current = FILTERS.getOrDefault(player.getUniqueId(), FilterMode.All);
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

    public static List<OrderRecord> pageOrders(Player player, OrderService service) {
        int page = page(player);
        List<OrderRecord> orders = filteredOrders(player, service);
        int start = (page - 1) * ORDERS_PER_PAGE;
        int end = Math.min(start + ORDERS_PER_PAGE, orders.size());

        if (start >= orders.size()) {
            return List.of();
        }

        return orders.subList(start, end);
    }

    private static List<OrderRecord> filteredOrders(Player player, OrderService service) {
        SortMode sort = SORTS.getOrDefault(player.getUniqueId(), SortMode.BestPay);
        FilterMode filter = FILTERS.getOrDefault(player.getUniqueId(), FilterMode.All);
        String search = SEARCHES.get(player.getUniqueId());

        return service.activeOrders().stream()
                .filter(order -> filter.matches(order.material()))
                .filter(order -> search == null || order.material().name().toLowerCase(Locale.ROOT).contains(search))
                .sorted(sort.comparator())
                .toList();
    }

    private static ItemStack sortItem(Player player) {
        SortMode active = SORTS.getOrDefault(player.getUniqueId(), SortMode.BestPay);

        return item(
                material("orders.gui.buttons.sort.material", Material.CAULDRON),
                cfg("orders.gui.buttons.sort.name", "&dSort"),
                List.of(
                        "&#bbbbbbClick to sort",
                        "",
                        line(active, SortMode.BestPay),
                        line(active, SortMode.PayEach),
                        line(active, SortMode.MostNeeded),
                        line(active, SortMode.Newest)
                )
        );
    }

    private static ItemStack filterItem(Player player) {
        FilterMode active = FILTERS.getOrDefault(player.getUniqueId(), FilterMode.All);

        return item(
                material("orders.gui.buttons.filter.material", Material.HOPPER),
                cfg("orders.gui.buttons.filter.name", "&dFilter"),
                List.of(
                        "&#bbbbbbClick to filter",
                        "",
                        line(active, FilterMode.All),
                        line(active, FilterMode.Blocks),
                        line(active, FilterMode.Tools),
                        line(active, FilterMode.Food),
                        line(active, FilterMode.Combat),
                        line(active, FilterMode.Potions),
                        line(active, FilterMode.Books),
                        line(active, FilterMode.Ingredients),
                        line(active, FilterMode.Utilities)
                )
        );
    }

    private static String line(Enum<?> active, Enum<?> value) {
        return (active == value ? "&#ff88ff" : "&#bbbbbb") + display(value.name());
    }

    public static ItemStack orderItem(OrderService service, OrderRecord order) {
        EconomyService economy = EconomyModule.economyService();

        long remaining = Math.max(0L, order.remainingAmount());
        long totalPayoutCents = remaining * order.pricePerItemCents();

        String pay = economy == null ? "$" + totalPayoutCents : economy.format(totalPayoutCents);
        String payEach = economy == null ? "$" + order.pricePerItemCents() : economy.format(order.pricePerItemCents());

        return item(
                order.material(),
                "&d" + service.pretty(order.material()),
                List.of(
                        "&#bbbbbbNeed: &#ff88ff" + remaining + "x " + service.pretty(order.material()),
                        "&#bbbbbbPay: &a" + pay,
                        "&#bbbbbbPay Each: &a" + payEach,
                        "&#bbbbbbBuyer: &#ff88ff" + order.ownerName(),
                        "&#bbbbbbProgress: &#ff88ff" + order.deliveredAmount() + "&#bbbbbb/&#ff88ff" + order.requestedAmount(),
                        "",
                        "&#bbbbbbBring matching items",
                        "&#ff88ffClick to deliver and get paid"
                )
        );
    }

    public static ItemStack item(Material material, String name, List<String> lore) {
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

    private static String cfg(String path, String fallback) {
        Core core = Core.instance();

        if (core == null) {
            return fallback;
        }

        return core.getConfig().getString(path, fallback);
    }

    private static List<String> lore(String path, List<String> fallback) {
        Core core = Core.instance();

        if (core == null || !core.getConfig().isList(path)) {
            return fallback;
        }

        return core.getConfig().getStringList(path);
    }

    private static Material material(String path, Material fallback) {
        Core core = Core.instance();

        if (core == null) {
            return fallback;
        }

        String raw = core.getConfig().getString(path, fallback.name());

        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        try {
            return Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
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

    private enum SortMode {
        BestPay,
        PayEach,
        MostNeeded,
        Newest;

        private SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        private Comparator<OrderRecord> comparator() {
            return switch (this) {
                case PayEach -> Comparator.comparingLong(OrderRecord::pricePerItemCents).reversed();
                case MostNeeded -> Comparator.comparingInt(OrderRecord::remainingAmount).reversed();
                case Newest -> Comparator.comparingLong(OrderRecord::createdAtMillis).reversed();
                case BestPay -> Comparator.comparingLong((OrderRecord order) ->
                        order.remainingAmount() * order.pricePerItemCents()).reversed();
            };
        }
    }

    private enum FilterMode {
        All,
        Blocks,
        Tools,
        Food,
        Combat,
        Potions,
        Books,
        Ingredients,
        Utilities;

        private FilterMode next() {
            FilterMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        private boolean matches(Material material) {
            if (this == All) {
                return true;
            }

            String name = material.name();

            return switch (this) {
                case Blocks -> material.isBlock();
                case Tools -> name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                        || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                        || name.equals("SHEARS") || name.equals("FISHING_ROD");
                case Food -> material.isEdible();
                case Combat -> name.endsWith("_SWORD") || name.endsWith("_HELMET")
                        || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                        || name.endsWith("_BOOTS") || name.equals("BOW")
                        || name.equals("CROSSBOW") || name.equals("SHIELD")
                        || name.equals("TRIDENT");
                case Potions -> name.contains("POTION") || name.equals("DRAGON_BREATH");
                case Books -> name.contains("BOOK") || name.equals("PAPER") || name.equals("MAP");
                case Ingredients -> Tag.ITEMS_COALS.isTagged(material)
                        || name.contains("INGOT") || name.contains("NUGGET")
                        || name.contains("DUST") || name.contains("GEM")
                        || name.contains("SHARD") || name.contains("SCRAP");
                case Utilities -> !material.isBlock() && !material.isEdible();
                case All -> true;
            };
        }
    }
}
