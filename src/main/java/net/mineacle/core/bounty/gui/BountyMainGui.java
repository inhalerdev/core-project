package net.mineacle.core.bounty;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BountyMainGui {

    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;

    public static final int PREVIOUS_SLOT = 45;
    public static final int SORT_SLOT = 48;
    public static final int REFRESH_SLOT = 49;
    public static final int SEARCH_SLOT = 50;
    public static final int NEXT_SLOT = 53;

    private static final int EMPTY_SLOT = 22;

    private static final Map<UUID, ViewState> STATES =
            new ConcurrentHashMap<>();

    private BountyMainGui() {
    }

    public static void open(
            Core core,
            Player player,
            BountyService bountyService,
            int requestedPage
    ) {
        ViewState state = state(player);
        List<BountyRecord> records = filteredRecords(
                bountyService,
                state
        );

        int totalPages = Math.max(
                1,
                (int) Math.ceil(
                        records.size()
                                / (double) ENTRIES_PER_PAGE
                )
        );
        int page = Math.max(
                0,
                Math.min(requestedPage, totalPages - 1)
        );

        MainHolder holder = new MainHolder(page);
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                legacy("Bounties (Page " + (page + 1) + ")")
        );
        holder.inventory = inventory;

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                records.size(),
                start + ENTRIES_PER_PAGE
        );

        for (int index = start; index < end; index++) {
            BountyRecord record = records.get(index);
            int slot = index - start;

            inventory.setItem(
                    slot,
                    bountyItem(record, bountyService)
            );
            holder.slotTargets.put(slot, record.targetId());
        }

        if (records.isEmpty()) {
            inventory.setItem(
                    EMPTY_SLOT,
                    emptyItem(!state.query().isBlank())
            );
        }

        if (page > 0) {
            inventory.setItem(
                    PREVIOUS_SLOT,
                    toolbar(
                            Material.ARROW,
                            "&dPrevious Page",
                            List.of(
                                    "&#bbbbbbPage &#ff88ff"
                                            + page
                            )
                    )
            );
        }

        inventory.setItem(
                SORT_SLOT,
                sortItem(state.sortMode())
        );
        inventory.setItem(
                REFRESH_SLOT,
                toolbar(
                        Material.EMERALD,
                        "&dRefresh",
                        List.of(
                                "&#bbbbbbClick to refresh bounties"
                        )
                )
        );
        inventory.setItem(
                SEARCH_SLOT,
                searchItem(state)
        );

        if (page < totalPages - 1) {
            inventory.setItem(
                    NEXT_SLOT,
                    toolbar(
                            Material.ARROW,
                            "&dNext Page",
                            List.of(
                                    "&#bbbbbbPage &#ff88ff"
                                            + (page + 2)
                            )
                    )
            );
        }

        player.openInventory(inventory);
    }

    public static MainHolder holder(Inventory inventory) {
        if (inventory == null
                || !(inventory.getHolder() instanceof MainHolder holder)) {
            return null;
        }

        return holder;
    }

    public static boolean isBountyInventory(Inventory inventory) {
        return inventory != null
                && (inventory.getHolder() instanceof MainHolder
                || inventory.getHolder() instanceof BountyConfirmGui.ConfirmHolder);
    }

    public static void cycleSort(Player player) {
        ViewState current = state(player);
        STATES.put(
                player.getUniqueId(),
                new ViewState(
                        current.sortMode().next(),
                        current.query(),
                        current.searchLabel()
                )
        );
    }

    public static void setSearch(
            Player player,
            String query,
            String displayLabel
    ) {
        ViewState current = state(player);
        String safeQuery = query == null
                ? ""
                : query.trim().toLowerCase(Locale.ROOT);
        String safeLabel = displayLabel == null
                ? safeQuery
                : displayLabel.trim();

        STATES.put(
                player.getUniqueId(),
                new ViewState(
                        current.sortMode(),
                        safeQuery,
                        safeLabel
                )
        );
    }

    public static void clearSearch(Player player) {
        ViewState current = state(player);
        STATES.put(
                player.getUniqueId(),
                new ViewState(
                        current.sortMode(),
                        "",
                        ""
                )
        );
    }

    public static boolean hasSearch(Player player) {
        return !state(player).query().isBlank();
    }

    public static BountySortMode sortMode(Player player) {
        return state(player).sortMode();
    }

    public static String search(Player player) {
        return state(player).query();
    }

    public static void clearState(Player player) {
        if (player != null) {
            STATES.remove(player.getUniqueId());
        }
    }

    public static void clearAllState() {
        STATES.clear();
    }

    private static ViewState state(Player player) {
        return STATES.getOrDefault(
                player.getUniqueId(),
                new ViewState(
                        BountySortMode.AMOUNT,
                        "",
                        ""
                )
        );
    }

    private static List<BountyRecord> filteredRecords(
            BountyService bountyService,
            ViewState state
    ) {
        List<BountyRecord> records = new ArrayList<>(
                bountyService.list(state.sortMode())
        );

        if (state.query().isBlank()) {
            return records;
        }

        records.removeIf(
                record -> !bountyService.matches(
                        record,
                        state.query()
                )
        );
        return records;
    }

    private static ItemStack bountyItem(
            BountyRecord record,
            BountyService bountyService
    ) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(
                record.targetId()
        );
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        String targetName = bountyService.displayName(record);
        long payout = bountyService.taxedPayout(
                record.amountCents()
        );

        meta.setOwningPlayer(target);
        meta.displayName(
                legacy("&#bbbbbb" + targetName)
        );

        List<Component> lore = new ArrayList<>();
        lore.add(
                legacy(
                        "&#bbbbbbBounty: &a"
                                + bountyService.format(
                                record.amountCents()
                        )
                )
        );

        if (payout != record.amountCents()) {
            lore.add(
                    legacy(
                            "&#bbbbbbReward: &a"
                                    + bountyService.format(payout)
                    )
            );
        }

        lore.add(legacy(""));
        lore.add(
                legacy(
                        "&#bbbbbbDefeat this player to claim the reward"
                )
        );
        lore.add(
                legacy(
                        "&#bbbbbbClick to view stats"
                )
        );

        meta.lore(noItalic(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack sortItem(
            BountySortMode current
    ) {
        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbCurrent: &#ff88ff"
                        + current.displayName()
        );
        lore.add("");

        for (BountySortMode mode : BountySortMode.values()) {
            lore.add(
                    (mode == current
                            ? "&#ff88ff"
                            : "&#bbbbbb")
                            + mode.displayName()
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

    private static ItemStack searchItem(ViewState state) {
        List<String> lore = new ArrayList<>();

        if (!state.query().isBlank()) {
            lore.add(
                    "&#bbbbbbCurrent: &#bbbbbb"
                            + state.searchLabel()
            );
            lore.add("");
            lore.add("&#bbbbbbLeft-click to search again");
            lore.add("&#bbbbbbRight-click to clear search");
        } else {
            lore.add("&#bbbbbbClick to search bounties");
            lore.add("&#bbbbbbType a player name in chat");
        }

        return toolbar(
                Material.OAK_SIGN,
                "&dSearch",
                lore
        );
    }

    private static ItemStack emptyItem(boolean searching) {
        if (searching) {
            return toolbar(
                    Material.BARRIER,
                    "&cNo Results",
                    List.of(
                            "&#bbbbbbNo matching bounty targets",
                            "&#bbbbbbUse Search to try again"
                    )
            );
        }

        return toolbar(
                Material.SKELETON_SKULL,
                "&dNo Bounties",
                List.of(
                        "&#bbbbbbNo one has a bounty right now",
                        "",
                        "&#bbbbbbPlace one with",
                        "&d/bounty set <player> <amount>"
                )
        );
    }

    private static ItemStack toolbar(
            Material material,
            String name,
            List<String> loreLines
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(legacy(name));

        List<Component> lore = new ArrayList<>();

        for (String line : loreLines) {
            lore.add(legacy(line));
        }

        meta.lore(noItalic(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static List<Component> noItalic(
            List<Component> input
    ) {
        List<Component> output = new ArrayList<>();

        for (Component component : input) {
            output.add(
                    component.decoration(
                            TextDecoration.ITALIC,
                            false
                    )
            );
        }

        return output;
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(
                        TextColor.color(text == null ? "" : text)
                )
                .decoration(TextDecoration.ITALIC, false);
    }

    private record ViewState(
            BountySortMode sortMode,
            String query,
            String searchLabel
    ) {
    }

    public static final class MainHolder
            implements InventoryHolder {

        private final Map<Integer, UUID> slotTargets =
                new LinkedHashMap<>();
        private final int page;
        private Inventory inventory;

        private MainHolder(int page) {
            this.page = page;
        }

        public int page() {
            return page;
        }

        public UUID targetAt(int slot) {
            return slotTargets.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
