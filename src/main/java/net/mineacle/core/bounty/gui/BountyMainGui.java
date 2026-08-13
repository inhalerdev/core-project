package net.mineacle.core.bounty.gui;

import net.kyori.adventure.text.Component;
import net.mineacle.core.bounty.BountyRecord;
import net.mineacle.core.bounty.BountySortMode;
import net.mineacle.core.bounty.service.BountyService;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiText;
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
import org.jetbrains.annotations.NotNull;

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

    private static final int[] TOOLBAR =
            CenteredToolbar
                    .interiorSlotsCenteredOn(
                            SIZE,
                            4,
                            1
                    );

    public static final int PREVIOUS_SLOT =
            CenteredToolbar
                    .previousSlot(SIZE);
    public static final int SORT_SLOT =
            TOOLBAR[0];
    public static final int REFRESH_SLOT =
            TOOLBAR[1];
    public static final int SEARCH_SLOT =
            TOOLBAR[2];
    public static final int PLACE_SLOT =
            TOOLBAR[3];
    public static final int NEXT_SLOT =
            CenteredToolbar
                    .nextSlot(SIZE);

    private static final int EMPTY_SLOT = 22;

    private static final Map<UUID, ViewState>
            STATES =
            new ConcurrentHashMap<>();

    private BountyMainGui() {
    }

    public static void open(
            Player player,
            BountyService bountyService,
            int requestedPage
    ) {
        ViewState state =
                state(player);
        List<BountyRecord> records =
                filteredRecords(
                        bountyService,
                        state
                );

        int totalPages =
                Math.max(
                        1,
                        Math.ceilDiv(
                                records.size(),
                                ENTRIES_PER_PAGE
                        )
                );
        int page =
                Math.clamp(
                        requestedPage,
                        0,
                        totalPages - 1
                );

        MainHolder holder =
                new MainHolder(
                        page,
                        page < totalPages - 1
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                "Bounties (Page "
                                        + (page + 1)
                                        + "/"
                                        + totalPages
                                        + ")"
                        )
                );
        holder.inventory = inventory;

        int start =
                page * ENTRIES_PER_PAGE;
        int end =
                Math.min(
                        records.size(),
                        start
                                + ENTRIES_PER_PAGE
                );

        for (int index = start;
             index < end;
             index++) {
            BountyRecord record =
                    records.get(index);
            int slot =
                    index - start;

            inventory.setItem(
                    slot,
                    bountyItem(
                            record,
                            bountyService
                    )
            );
            holder.slotTargets.put(
                    slot,
                    record.targetId()
            );
        }

        if (records.isEmpty()) {
            inventory.setItem(
                    EMPTY_SLOT,
                    emptyItem(
                            state.query()
                    )
            );
        }

        if (page > 0) {
            inventory.setItem(
                    PREVIOUS_SLOT,
                    navigationItem(
                            true,
                            page
                    )
            );
        }

        inventory.setItem(
                SORT_SLOT,
                sortItem(
                        state.sortMode()
                )
        );
        inventory.setItem(
                REFRESH_SLOT,
                item(
                        Material.EMERALD,
                        "&#B078FFRefresh",
                        "&#bbbbbbReload current bounties"
                )
        );
        inventory.setItem(
                SEARCH_SLOT,
                searchItem(state)
        );
        inventory.setItem(
                PLACE_SLOT,
                item(
                        Material.GOLD_INGOT,
                        "&#B078FFPlace Bounty",
                        "&#bbbbbbClick to choose a player",
                        "",
                        "&#D0AFFF/bounty set <player> <amount>"
                )
        );

        if (page
                < totalPages - 1) {
            inventory.setItem(
                    NEXT_SLOT,
                    navigationItem(
                            false,
                            page + 2
                    )
            );
        }

        player.openInventory(
                inventory
        );
    }

    public static MainHolder holder(
            Inventory inventory
    ) {
        if (inventory == null
                || !(inventory.getHolder()
                instanceof MainHolder holder)) {
            return null;
        }

        return holder;
    }

    public static boolean isBountyInventory(
            Inventory inventory
    ) {
        return inventory != null
                && (
                inventory.getHolder()
                        instanceof MainHolder
                        || inventory.getHolder()
                        instanceof BountyConfirmGui
                        .ConfirmHolder
        );
    }

    public static void cycleSort(
            Player player,
            boolean reverse
    ) {
        ViewState current =
                state(player);
        BountySortMode next =
                reverse
                        ? current.sortMode()
                        .previous()
                        : current.sortMode()
                        .next();

        STATES.put(
                player.getUniqueId(),
                new ViewState(
                        next,
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
        ViewState current =
                state(player);
        String safeQuery =
                query == null
                        ? ""
                        : query.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );
        String safeLabel =
                displayLabel == null
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

    public static void clearSearch(
            Player player
    ) {
        ViewState current =
                state(player);

        STATES.put(
                player.getUniqueId(),
                new ViewState(
                        current.sortMode(),
                        "",
                        ""
                )
        );
    }

    public static boolean hasSearch(
            Player player
    ) {
        return !state(player)
                .query()
                .isBlank();
    }

    public static BountySortMode sortMode(
            Player player
    ) {
        return state(player)
                .sortMode();
    }

    public static String search(
            Player player
    ) {
        return state(player)
                .query();
    }

    public static void clearState(
            Player player
    ) {
        if (player != null) {
            STATES.remove(
                    player.getUniqueId()
            );
        }
    }

    public static void clearAllState() {
        STATES.clear();
    }

    private static ViewState state(
            Player player
    ) {
        return STATES.getOrDefault(
                player.getUniqueId(),
                new ViewState(
                        BountySortMode.AMOUNT,
                        "",
                        ""
                )
        );
    }

    private static List<BountyRecord>
    filteredRecords(
            BountyService bountyService,
            ViewState state
    ) {
        List<BountyRecord> records =
                new ArrayList<>(
                        bountyService.list(
                                state.sortMode()
                        )
                );

        if (state.query()
                .isBlank()) {
            return records;
        }

        records.removeIf(
                record ->
                        !bountyService.matches(
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
        OfflinePlayer target =
                Bukkit.getOfflinePlayer(
                        record.targetId()
                );
        ItemStack item =
                new ItemStack(
                        Material.PLAYER_HEAD
                );
        ItemMeta rawMeta =
                item.getItemMeta();

        if (!(rawMeta
                instanceof SkullMeta meta)) {
            return item;
        }

        String targetName =
                bountyService
                        .displayName(record);
        long payout =
                bountyService
                        .taxedPayout(
                                record.amountCents()
                        );

        meta.setOwningPlayer(target);
        meta.displayName(
                GuiText.component(
                        "&#B078FF"
                                + targetName
                )
        );

        List<Component> lore =
                new ArrayList<>();

        lore.add(
                GuiText.component(
                        "&#bbbbbbBounty: &a"
                                + bountyService.format(
                                record.amountCents()
                        )
                )
        );

        if (payout
                != record.amountCents()) {
            lore.add(
                    GuiText.component(
                            "&#bbbbbbReward: &a"
                                    + bountyService.format(
                                    payout
                            )
                    )
            );
        }

        lore.add(
                GuiText.component(
                        bountyService
                                .isOnline(record)
                                ? "&#bbbbbbStatus: &#D0AFFFOnline"
                                : "&#bbbbbbStatus: Offline"
                )
        );
        lore.add(
                GuiText.component(
                        "&#bbbbbbUpdated: &#D0AFFF"
                                + bountyService.ageText(
                                record.lastUpdated()
                        )
                )
        );
        lore.add(Component.empty());
        lore.add(
                GuiText.component(
                        "&#bbbbbbClick to view stats"
                )
        );
        lore.add(
                GuiText.component(
                        "&#D0AFFFShift-click to add bounty"
                )
        );

        meta.lore(
                List.copyOf(lore)
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);

        return item;
    }

    private static ItemStack sortItem(
            BountySortMode current
    ) {
        List<String> lore =
                new ArrayList<>();

        lore.add(
                "&#bbbbbbCurrent: &#D0AFFF"
                        + current.displayName()
        );
        lore.add("");

        for (BountySortMode mode
                : BountySortMode.values()) {
            lore.add(
                    (
                            mode == current
                                    ? "&#D0AFFF"
                                    : "&#bbbbbb"
                    )
                            + mode.displayName()
            );
        }

        lore.add("");
        lore.add(
                "&#bbbbbbLeft-click: Next"
        );
        lore.add(
                "&#bbbbbbRight-click: Previous"
        );

        return item(
                Material.ANVIL,
                "&#B078FFSort",
                lore.toArray(
                        String[]::new
                )
        );
    }

    private static ItemStack searchItem(
            ViewState state
    ) {
        if (state.query()
                .isBlank()) {
            return item(
                    Material.OAK_SIGN,
                    "&#B078FFSearch",
                    "&#bbbbbbClick to search players"
            );
        }

        return item(
                Material.OAK_SIGN,
                "&#B078FFSearch",
                "&#bbbbbbCurrent: &#D0AFFF"
                        + state.searchLabel(),
                "",
                "&#bbbbbbClick to replace",
                "&#bbbbbbRight-click to clear"
        );
    }

    private static ItemStack emptyItem(
            String query
    ) {
        if (query != null
                && !query.isBlank()) {
            return item(
                    Material.GRAY_DYE,
                    "&#bbbbbbNo Results",
                    "&#bbbbbbNo matching bounty targets"
            );
        }

        return item(
                Material.SKELETON_SKULL,
                "&#bbbbbbNo Bounties",
                "&#bbbbbbNo active bounties",
                "",
                "&#D0AFFFUse Place Bounty to create one"
        );
    }

    private static ItemStack navigationItem(
            boolean previous,
            int targetPage
    ) {
        return item(
                Material.ARROW,
                previous
                        ? "&#B078FFPrevious Page"
                        : "&#B078FFNext Page",
                "&#bbbbbbPage &#D0AFFF"
                        + targetPage
        );
    }

    private static ItemStack item(
            Material material,
            String name,
            String... loreLines
    ) {
        ItemStack item =
                new ItemStack(material);
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(
                GuiText.component(name)
        );
        meta.lore(
                GuiText.lore(
                        List.of(
                                loreLines
                        )
                )
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS
        );
        item.setItemMeta(meta);

        return item;
    }

    private record ViewState(
            BountySortMode sortMode,
            String query,
            String searchLabel
    ) {
    }

    public static final class MainHolder
            implements InventoryHolder {

        private final Map<Integer, UUID>
                slotTargets =
                new LinkedHashMap<>();
        private final int page;
        private final boolean hasNext;
        private Inventory inventory;

        private MainHolder(
                int page,
                boolean hasNext
        ) {
            this.page = page;
            this.hasNext = hasNext;
        }

        public int page() {
            return page;
        }

        public boolean hasNext() {
            return hasNext;
        }

        public UUID targetAt(
                int slot
        ) {
            return slotTargets.get(slot);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
