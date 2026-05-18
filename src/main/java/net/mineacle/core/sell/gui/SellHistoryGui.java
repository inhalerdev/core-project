package net.mineacle.core.sell.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.model.SellHistoryEntry;
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
import java.util.Map;
import java.util.UUID;

public final class SellHistoryGui {

    public static final String TITLE_PREFIX = "Sell History (Page ";
    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;

    public static final int PREVIOUS_SLOT = 45;
    public static final int SORT_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    public static final String META_PAGE = "mineacle_sell_history_page";

    private static final Map<UUID, SortMode> SORTS = new HashMap<>();

    private SellHistoryGui() {
    }

    public static void open(Core core, Player player, SellService sellService, int page) {
        List<SellHistoryEntry> entries = entries(player, sellService);
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) ENTRIES_PER_PAGE));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inventory = Bukkit.createInventory(null, SIZE, TITLE_PREFIX + (safePage + 1) + ")");
        player.setMetadata(META_PAGE, new FixedMetadataValue(core, safePage));

        if (entries.isEmpty()) {
            inventory.setItem(22, item(
                    Material.CHEST,
                    "&dNo Sell History",
                    List.of(
                            "&#bbbbbbSell items with &#ff6fff/sell",
                            "&#bbbbbbYour sold items will appear here"
                    )
            ));
        } else {
            int start = safePage * ENTRIES_PER_PAGE;
            int end = Math.min(entries.size(), start + ENTRIES_PER_PAGE);

            for (int index = start; index < end; index++) {
                SellHistoryEntry entry = entries.get(index);
                inventory.setItem(index - start, historyItem(sellService, entry));
            }
        }

        if (safePage > 0) {
            inventory.setItem(PREVIOUS_SLOT, item(
                    Material.ARROW,
                    "&dBack",
                    List.of("&#bbbbbbClick to go to the previous page")
            ));
        }

        inventory.setItem(SORT_SLOT, sortItem(currentSort(player)));

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

    public static void cycleSort(Player player) {
        SORTS.put(player.getUniqueId(), currentSort(player).next());
    }

    public static SortMode currentSort(Player player) {
        return SORTS.getOrDefault(player.getUniqueId(), SortMode.RECENTLY_SOLD);
    }

    private static List<SellHistoryEntry> entries(Player player, SellService sellService) {
        List<SellHistoryEntry> entries = new ArrayList<>(sellService.history(player.getUniqueId()));
        SortMode sort = currentSort(player);

        entries.sort(switch (sort) {
            case AMOUNT -> Comparator.comparingLong(SellHistoryEntry::amount).reversed();
            case NAME_A_Z -> Comparator.comparing(entry -> sellService.pretty(entry.material()), String.CASE_INSENSITIVE_ORDER);
            case TOTAL_PRICE -> Comparator.comparingLong(SellHistoryEntry::totalCents).reversed();
            case RECENTLY_SOLD -> Comparator.comparingLong(SellHistoryEntry::lastSoldMillis).reversed();
        });

        return entries;
    }

    private static ItemStack historyItem(SellService sellService, SellHistoryEntry entry) {
        return item(
                entry.material(),
                "&d" + sellService.pretty(entry.material()),
                List.of(
                        "&#bbbbbbTotal Price: &a" + sellService.format(entry.totalCents()),
                        "&#bbbbbbTotal Amount: &#ff6fff" + MoneyFormatter.compact(entry.amount()),
                        "&#bbbbbbCategory: &#ff6fff" + sellService.categoryDisplay(entry.material())
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

        return item(Material.ANVIL, "&dSell History", lore);
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
        RECENTLY_SOLD("Recently Sold"),
        AMOUNT("Amount"),
        NAME_A_Z("Name A-Z"),
        TOTAL_PRICE("Total Price");

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
}
