package net.mineacle.core.orders.gui;

import net.mineacle.core.orders.model.OrderRecord;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrdersViewState {

    private static final Map<UUID, MainState> MAIN =
            new ConcurrentHashMap<>();
    private static final Map<UUID, CreateState> CREATE =
            new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> YOUR_PAGES =
            new ConcurrentHashMap<>();

    private OrdersViewState() {
    }

    public static MainState main(Player player) {
        return MAIN.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new MainState()
        );
    }

    public static CreateState create(Player player) {
        return CREATE.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new CreateState()
        );
    }

    public static int yourPage(Player player) {
        return Math.max(
                1,
                YOUR_PAGES.getOrDefault(
                        player.getUniqueId(),
                        1
                )
        );
    }

    public static void setYourPage(
            Player player,
            int page
    ) {
        YOUR_PAGES.put(
                player.getUniqueId(),
                Math.max(1, page)
        );
    }

    public static void clear(Player player) {
        if (player != null) {
            clear(player.getUniqueId());
        }
    }

    public static void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }

        MAIN.remove(playerId);
        CREATE.remove(playerId);
        YOUR_PAGES.remove(playerId);
    }

    public static void clearAll() {
        MAIN.clear();
        CREATE.clear();
        YOUR_PAGES.clear();
    }

    public static final class MainState {

        private int page = 1;
        private SortMode sort = SortMode.BEST_PAY;
        private MainFilter filter = MainFilter.ALL;
        private String query = "";

        public int page() {
            return page;
        }

        public void page(int page) {
            this.page = Math.max(1, page);
        }

        public SortMode sort() {
            return sort;
        }

        public void cycleSort() {
            sort = sort.next();
            page = 1;
        }

        public MainFilter filter() {
            return filter;
        }

        public void cycleFilter() {
            filter = filter.next();
            page = 1;
        }

        public String query() {
            return query;
        }

        public boolean hasQuery() {
            return !query.isBlank();
        }

        public void query(String query) {
            this.query = normalizeQuery(query);
            page = 1;
        }

        public void clearQuery() {
            query = "";
            page = 1;
        }
    }

    public static final class CreateState {

        private int page = 1;
        private CreateFilter filter = CreateFilter.BLOCKS;
        private String query = "";
        private Material selected;

        public int page() {
            return page;
        }

        public void page(int page) {
            this.page = Math.max(1, page);
        }

        public CreateFilter filter() {
            return filter;
        }

        public void cycleFilter() {
            filter = filter.next();
            page = 1;
        }

        public String query() {
            return query;
        }

        public boolean hasQuery() {
            return !query.isBlank();
        }

        public void query(String query) {
            this.query = normalizeQuery(query);
            page = 1;
        }

        public void clearQuery() {
            query = "";
            page = 1;
        }

        public Material selected() {
            return selected;
        }

        public void selected(Material selected) {
            this.selected = selected;
        }

        public void clearSelected() {
            selected = null;
        }
    }

    public enum SortMode {
        BEST_PAY("Best Pay"),
        PAY_EACH("Pay Each"),
        MOST_NEEDED("Most Needed"),
        NEWEST("Newest");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public Comparator<OrderRecord> comparator() {
            return switch (this) {
                case BEST_PAY -> Comparator
                        .comparingLong(
                                OrderRecord::escrowRemainingCents
                        )
                        .reversed()
                        .thenComparing(
                                Comparator.comparingLong(
                                        OrderRecord::createdAtMillis
                                ).reversed()
                        );
                case PAY_EACH -> Comparator
                        .comparingLong(
                                OrderRecord::pricePerItemCents
                        )
                        .reversed()
                        .thenComparing(
                                Comparator.comparingLong(
                                        OrderRecord::createdAtMillis
                                ).reversed()
                        );
                case MOST_NEEDED -> Comparator
                        .comparingInt(
                                OrderRecord::remainingAmount
                        )
                        .reversed()
                        .thenComparing(
                                Comparator.comparingLong(
                                        OrderRecord::createdAtMillis
                                ).reversed()
                        );
                case NEWEST -> Comparator
                        .comparingLong(
                                OrderRecord::createdAtMillis
                        )
                        .reversed();
            };
        }
    }

    public enum MainFilter {
        ALL("All"),
        BLOCKS("Blocks"),
        TOOLS("Tools"),
        FOOD("Food"),
        COMBAT("Combat"),
        POTIONS("Potions"),
        BOOKS("Books"),
        INGREDIENTS("Ingredients"),
        UTILITIES("Utilities");

        private final String label;

        MainFilter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public MainFilter next() {
            MainFilter[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public boolean matches(Material material) {
            if (this == ALL) {
                return true;
            }

            String name = material.name();

            return switch (this) {
                case BLOCKS -> material.isBlock();
                case TOOLS -> isTool(name);
                case FOOD -> material.isEdible();
                case COMBAT -> isCombat(name);
                case POTIONS -> name.contains("POTION")
                        || name.equals("DRAGON_BREATH");
                case BOOKS -> name.contains("BOOK")
                        || name.equals("PAPER")
                        || name.endsWith("_MAP")
                        || name.equals("MAP");
                case INGREDIENTS -> Tag.ITEMS_COALS
                        .isTagged(material)
                        || name.contains("INGOT")
                        || name.contains("NUGGET")
                        || name.contains("DUST")
                        || name.contains("GEM")
                        || name.contains("SHARD")
                        || name.contains("SCRAP")
                        || name.startsWith("RAW_");
                case UTILITIES -> !material.isBlock()
                        && !material.isEdible()
                        && !isTool(name)
                        && !isCombat(name);
                case ALL -> true;
            };
        }
    }

    public enum CreateFilter {
        BLOCKS("Blocks"),
        LOGS("Logs"),
        ORES("Ores"),
        FARMING("Farming"),
        MOB_DROPS("Mob Drops"),
        FOOD("Food"),
        TOOLS("Tools"),
        COMBAT("Combat"),
        ALL_ITEMS("All Items");

        private final String label;

        CreateFilter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public CreateFilter next() {
            CreateFilter[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public boolean matches(Material material) {
            String name = material.name();

            return switch (this) {
                case BLOCKS -> material.isBlock();
                case LOGS -> name.endsWith("_LOG")
                        || name.endsWith("_WOOD")
                        || name.endsWith("_STEM")
                        || name.endsWith("_HYPHAE");
                case ORES -> name.contains("ORE")
                        || name.startsWith("RAW_")
                        || name.contains("INGOT")
                        || name.contains("NUGGET")
                        || name.contains("DIAMOND")
                        || name.contains("EMERALD")
                        || name.contains("LAPIS")
                        || name.contains("REDSTONE");
                case FARMING -> Tag.ITEMS_WOOL.isTagged(material)
                        || name.contains("WHEAT")
                        || name.contains("CARROT")
                        || name.contains("POTATO")
                        || name.contains("BEETROOT")
                        || name.contains("PUMPKIN")
                        || name.contains("MELON")
                        || name.contains("SEEDS")
                        || name.contains("SUGAR_CANE")
                        || name.contains("CACTUS")
                        || name.contains("BAMBOO");
                case MOB_DROPS -> name.contains("BONE")
                        || name.contains("ROTTEN")
                        || name.contains("STRING")
                        || name.contains("SPIDER")
                        || name.contains("GUNPOWDER")
                        || name.contains("PEARL")
                        || name.contains("BLAZE")
                        || name.contains("GHAST")
                        || name.contains("LEATHER")
                        || name.contains("FEATHER")
                        || name.contains("SLIME")
                        || name.contains("MAGMA");
                case FOOD -> material.isEdible();
                case TOOLS -> isTool(name);
                case COMBAT -> isCombat(name);
                case ALL_ITEMS -> true;
            };
        }
    }

    private static boolean isTool(String name) {
        return name.endsWith("_PICKAXE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.equals("SHEARS")
                || name.equals("FISHING_ROD")
                || name.equals("BRUSH")
                || name.equals("FLINT_AND_STEEL");
    }

    private static boolean isCombat(String name) {
        return name.endsWith("_SWORD")
                || name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || name.equals("BOW")
                || name.equals("CROSSBOW")
                || name.equals("SHIELD")
                || name.equals("TRIDENT")
                || name.equals("MACE")
                || name.equals("ARROW")
                || name.endsWith("_ARROW");
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }

        String normalized = query
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_');

        if (normalized.equals("clear")
                || normalized.equals("cancel")
                || normalized.equals("cancelled")) {
            return "";
        }

        return normalized;
    }
}
