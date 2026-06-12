package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BountyMainGui {

    public static final String TITLE_PREFIX = "Bounties (Page ";
    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;

    public static final int PREVIOUS_SLOT = 45;
    public static final int SORT_SLOT = 48;
    public static final int REFRESH_SLOT = 49;
    public static final int SEARCH_SLOT = 50;
    public static final int NEXT_SLOT = 53;

    public static final String META_PAGE = "mineacle_bounty_page";
    public static final String META_SORT = "mineacle_bounty_sort";

    private static final Map<UUID, String> SEARCHES = new HashMap<>();

    private BountyMainGui() {
    }

    public static void open(Core core, Player player, BountyService bountyService, int page) {
        List<BountyRecord> records = filteredRecords(player, bountyService);
        int totalPages = Math.max(1, (int) Math.ceil(records.size() / (double) ENTRIES_PER_PAGE));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inventory = Bukkit.createInventory(null, SIZE, TITLE_PREFIX + (safePage + 1) + ")");

        player.setMetadata(META_PAGE, new FixedMetadataValue(core, safePage));

        int start = safePage * ENTRIES_PER_PAGE;
        int end = Math.min(records.size(), start + ENTRIES_PER_PAGE);

        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, bountyItem(records.get(index), bountyService));
        }

        if (records.isEmpty()) {
            inventory.setItem(22, item(
                    Material.SKELETON_SKULL,
                    "&dNo Bounties",
                    List.of(
                            "&#bbbbbbNo one has a bounty right now",
                            "",
                            "&#bbbbbbPlace one with:",
                            "&#ff88ff/bounty add <player> <amount>"
                    )
            ));
        }

        if (safePage > 0) {
            inventory.setItem(PREVIOUS_SLOT, item(
                    Material.ARROW,
                    "&dBack",
                    List.of("&#bbbbbbPrevious page")
            ));
        }

        inventory.setItem(SORT_SLOT, sortItem(sortMode(player)));

        inventory.setItem(REFRESH_SLOT, item(
                Material.EMERALD,
                "&dRefresh",
                List.of("&#bbbbbbClick to refresh")
        ));

        inventory.setItem(SEARCH_SLOT, item(
                Material.OAK_SIGN,
                "&dSearch",
                List.of("&#bbbbbbSearch for a bounty target")
        ));

        if (safePage < totalPages - 1) {
            inventory.setItem(NEXT_SLOT, item(
                    Material.ARROW,
                    "&dNext",
                    List.of("&#bbbbbbNext page")
            ));
        }

        player.openInventory(inventory);
    }

    public static boolean isTitle(String title) {
        return title != null && title.startsWith(TITLE_PREFIX);
    }

    public static int currentPage(Player player) {
        if (!player.hasMetadata(META_PAGE)) {
            return 0;
        }

        return player.getMetadata(META_PAGE).get(0).asInt();
    }

    public static BountySortMode sortMode(Player player) {
        if (!player.hasMetadata(META_SORT)) {
            return BountySortMode.AMOUNT;
        }

        try {
            return BountySortMode.valueOf(player.getMetadata(META_SORT).get(0).asString());
        } catch (IllegalArgumentException exception) {
            return BountySortMode.AMOUNT;
        }
    }

    public static void cycleSort(Core core, Player player) {
        BountySortMode next = sortMode(player).next();
        player.setMetadata(META_SORT, new FixedMetadataValue(core, next.name()));
    }

    public static void setSearch(Player player, String query) {
        if (query == null || query.isBlank()) {
            SEARCHES.remove(player.getUniqueId());
            return;
        }

        SEARCHES.put(player.getUniqueId(), query.toLowerCase());
    }

    public static void clearSearch(Player player) {
        SEARCHES.remove(player.getUniqueId());
    }

    public static String search(Player player) {
        return SEARCHES.getOrDefault(player.getUniqueId(), "");
    }

    public static List<BountyRecord> filteredRecords(Player player, BountyService bountyService) {
        List<BountyRecord> records = new ArrayList<>(bountyService.list(sortMode(player)));
        String query = search(player);

        if (query == null || query.isBlank()) {
            return records;
        }

        records.removeIf(record -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(record.targetId());
            String username = target.getName() == null ? "" : target.getName();
            String displayName = DisplayNames.displayName(target);

            return !username.toLowerCase().contains(query)
                    && !displayName.toLowerCase().contains(query)
                    && !record.targetName().toLowerCase().contains(query);
        });

        return records;
    }

    public static UUID targetAt(Player player, BountyService bountyService, int slot) {
        if (slot < 0 || slot >= ENTRIES_PER_PAGE) {
            return null;
        }

        List<BountyRecord> records = filteredRecords(player, bountyService);
        int index = (currentPage(player) * ENTRIES_PER_PAGE) + slot;

        if (index < 0 || index >= records.size()) {
            return null;
        }

        return records.get(index).targetId();
    }

    private static ItemStack bountyItem(BountyRecord record, BountyService bountyService) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(record.targetId());
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setOwningPlayer(target);
        meta.setDisplayName(TextColor.color("&d" + DisplayNames.displayName(target)));
        meta.setLore(List.of(
                TextColor.color("&#bbbbbbTarget: &#ff88ff" + DisplayNames.displayName(target)),
                TextColor.color("&#bbbbbbReward: &a" + bountyService.format(record.amountCents())),
                TextColor.color("&#bbbbbbClaim: &#ff88ffDefeat this player"),
                "",
                TextColor.color("&#bbbbbbClick to view stats")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack sortItem(BountySortMode current) {
        List<String> lore = new ArrayList<>();

        lore.add("&#bbbbbbClick to sort");
        lore.add("");

        for (BountySortMode mode : BountySortMode.values()) {
            lore.add((mode == current ? "&#ff88ff" : "&#bbbbbb") + mode.displayName());
        }

        return item(Material.HOPPER, "&dSort", lore);
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
}
