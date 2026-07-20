package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.text.TextColor;
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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorthGui {

    public static final int SIZE = 54;

    private static final int[] TOOLBAR =
            CenteredToolbar.interiorSlots(SIZE, 4);

    public static final int PREVIOUS_SLOT =
            CenteredToolbar.previousSlot(SIZE);
    public static final int SEARCH_SLOT = TOOLBAR[0];
    public static final int SORT_SLOT = TOOLBAR[1];
    public static final int FILTER_SLOT = TOOLBAR[2];
    public static final int REFRESH_SLOT = TOOLBAR[3];
    public static final int NEXT_SLOT =
            CenteredToolbar.nextSlot(SIZE);

    private static final int CONTENT_SLOTS = 45;
    private static final long CATALOG_TTL_MILLIS =
            5L * 60L * 1000L;

    private static final Map<UUID, Integer> PAGES =
            new java.util.HashMap<>();
    private static final Map<UUID, SortMode> SORTS =
            new java.util.HashMap<>();
    private static final Map<UUID, FilterMode> FILTERS =
            new java.util.HashMap<>();

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
                SEARCH_SLOT,
                toolbar(
                        Material.OAK_SIGN,
                        "&dSearch",
                        List.of(
                                "&#bbbbbbUse &d/worth <item>",
                                "&#bbbbbbExample: &d/worth diamond"
                        )
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

        for (Material material : Material.values()) {
            if (!shouldShowInWorth(material)
                    || sellService.baseWorthCents(material) <= 0L) {
                continue;
            }

            CATALOG.add(material);
        }

        catalogBuiltAt = now;
    }

    private static List<Material> filtered(
            Player player,
            SellService sellService
    ) {
        FilterMode filter = filter(player);
        List<Material> result = CATALOG.stream()
                .filter(material ->
                        filter.matches(sellService, material)
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

        long unit = sellService.unitWorthCents(
                player,
                material
        );
        int stackSize = Math.max(
                1,
                material.getMaxStackSize()
        );
        long stackPrice;

        try {
            stackPrice = Math.multiplyExact(unit, stackSize);
        } catch (ArithmeticException exception) {
            stackPrice = Long.MAX_VALUE;
        }

        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbWorth: &a"
                        + sellService.format(unit)
        );

        if (material != Material.DRAGON_EGG) {
            lore.add(
                    "&#bbbbbbStack Price: &a"
                            + sellService.format(stackPrice)
            );
        } else {
            lore.add("");
            lore.add("&cNot sellable");
            lore.add(
                    "&#bbbbbbUnique server trophy item"
            );
        }

        meta.setDisplayName(
                TextColor.color(
                        "&#bbbbbb"
                                + sellService.pretty(material)
                )
        );
        meta.setLore(
                lore.stream().map(TextColor::color).toList()
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
                previous ? "&dPrevious Page" : "&dNext Page",
                List.of(
                        "&#bbbbbbPage &#ff88ff" + targetPage
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
        return toolbar(Material.ANVIL, "&dSort", lore);
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
        return toolbar(Material.HOPPER, "&dFilter", lore);
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
                lore.stream().map(TextColor::color).toList()
        );
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean shouldShowInWorth(
            Material material
    ) {
        if (material == null
                || material == Material.AIR
                || !material.isItem()) {
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
        COMBAT("Combat"),
        BLOCKS("Blocks"),
        MISC("Misc");

        private final String display;

        FilterMode(String display) {
            this.display = display;
        }

        private FilterMode next() {
            FilterMode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
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
                    name().toLowerCase(java.util.Locale.ROOT)
            );
        }
    }

    private static final Set<String> BLOCKED_WORTH_ITEMS =
            new HashSet<>(Set.of(
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
                    "FARMLAND",
                    "FROGSPAWN",
                    "REINFORCED_DEEPSLATE",
                    "PLAYER_HEAD",
                    "PLAYER_WALL_HEAD"
            ));

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
