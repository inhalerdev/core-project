package net.mineacle.core.baltop.gui;

import net.kyori.adventure.text.Component;
import net.mineacle.core.baltop.service.BalTopLeaderboardCache;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiSearchLore;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BalTopGui {

    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;

    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";
    private static final String MONEY = "&#11fc7b";

    private static final int[] TOOLBAR =
            CenteredToolbar.interiorSlots(SIZE, 3);

    private static final int SLOT_PREVIOUS =
            CenteredToolbar.previousSlot(SIZE);
    private static final int SLOT_PLAYER_HEAD = TOOLBAR[0];
    private static final int SLOT_REFRESH = TOOLBAR[1];
    private static final int SLOT_SEARCH = TOOLBAR[2];
    private static final int SLOT_NEXT =
            CenteredToolbar.nextSlot(SIZE);
    private static final int SLOT_EMPTY = 22;

    private static final Map<UUID, SearchState> SEARCHES =
            new ConcurrentHashMap<>();

    private BalTopGui() {
    }

    public static void open(
            Player player,
            EconomyService economyService,
            BalTopLeaderboardCache leaderboardCache,
            int requestedPage
    ) {
        BalTopLeaderboardCache.Snapshot snapshot =
                leaderboardCache.current();
        SearchState search =
                SEARCHES.get(player.getUniqueId());
        List<BalTopLeaderboardCache.Entry> visibleEntries =
                search == null
                        ? snapshot.entries()
                        : snapshot.search(search.query());

        int totalPages = Math.max(
                1,
                (int) Math.ceil(
                        visibleEntries.size()
                                / (double) ENTRIES_PER_PAGE
                )
        );
        int page = Math.clamp(
                requestedPage,
                0,
                totalPages - 1
        );

        BalTopHolder holder =
                new BalTopHolder(page);
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                "Balance Top (Page "
                                        + (page + 1)
                                        + ")"
                        )
                );
        holder.inventory = inventory;

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                visibleEntries.size(),
                start + ENTRIES_PER_PAGE
        );

        for (int index = start;
             index < end;
             index++) {
            BalTopLeaderboardCache.Entry entry =
                    visibleEntries.get(index);
            int slot = index - start;

            inventory.setItem(
                    slot,
                    playerEntry(
                            entry,
                            economyService
                    )
            );
            holder.slotTargets.put(
                    slot,
                    entry.playerId()
            );
        }

        if (visibleEntries.isEmpty()) {
            inventory.setItem(
                    SLOT_EMPTY,
                    emptyItem(search != null)
            );
        }

        if (page > 0) {
            inventory.setItem(
                    SLOT_PREVIOUS,
                    navigationItem(
                            true,
                            page
                    )
            );
        }

        inventory.setItem(
                SLOT_PLAYER_HEAD,
                selfHead(
                        player,
                        economyService,
                        snapshot.player(
                                player.getUniqueId()
                        )
                )
        );

        inventory.setItem(
                SLOT_REFRESH,
                toolbar(
                        Material.EMERALD,
                        SECONDARY + "Refresh",
                        List.of(
                                BODY + "Click to refresh"
                        )
                )
        );

        inventory.setItem(
                SLOT_SEARCH,
                searchItem(search)
        );

        if (page < totalPages - 1) {
            inventory.setItem(
                    SLOT_NEXT,
                    navigationItem(
                            false,
                            page + 2
                    )
            );
        }

        player.openInventory(inventory);
    }

    public static boolean isBalTopInventory(
            Inventory inventory
    ) {
        return inventory != null
                && inventory.getHolder()
                instanceof BalTopHolder;
    }

    public static BalTopHolder holder(
            Inventory inventory
    ) {
        if (inventory == null
                || !(inventory.getHolder()
                instanceof BalTopHolder holder)) {
            return null;
        }

        return holder;
    }

    public static int previousSlot() {
        return SLOT_PREVIOUS;
    }

    public static int playerHeadSlot() {
        return SLOT_PLAYER_HEAD;
    }

    public static int refreshSlot() {
        return SLOT_REFRESH;
    }

    public static int searchSlot() {
        return SLOT_SEARCH;
    }

    public static int nextSlot() {
        return SLOT_NEXT;
    }

    public static void setSearch(
            Player player,
            String query,
            String displayLabel
    ) {
        if (player == null
                || query == null
                || query.isBlank()) {
            clearSearch(player);
            return;
        }

        String label =
                displayLabel == null
                        || displayLabel.isBlank()
                        ? query.trim()
                        : displayLabel.trim();

        SEARCHES.put(
                player.getUniqueId(),
                new SearchState(
                        query.trim(),
                        label
                )
        );
    }

    public static void clearSearch(
            Player player
    ) {
        if (player != null) {
            SEARCHES.remove(
                    player.getUniqueId()
            );
        }
    }

    public static boolean hasSearch(
            Player player
    ) {
        return player != null
                && SEARCHES.containsKey(
                player.getUniqueId()
        );
    }

    public static void clearAllState() {
        SEARCHES.clear();
    }

    public static boolean hasMatches(
            Player player,
            BalTopLeaderboardCache leaderboardCache
    ) {
        if (player == null) {
            return false;
        }

        SearchState search =
                SEARCHES.get(player.getUniqueId());
        BalTopLeaderboardCache.Snapshot snapshot =
                leaderboardCache.current();

        return search == null
                ? !snapshot.entries().isEmpty()
                : !snapshot.search(
                search.query()
        ).isEmpty();
    }

    private static ItemStack navigationItem(
            boolean previous,
            int targetPage
    ) {
        return toolbar(
                Material.ARROW,
                SECONDARY
                        + (previous
                        ? "Previous Page"
                        : "Next Page"),
                List.of(
                        BODY + "Page "
                                + ACCENT
                                + targetPage
                )
        );
    }

    private static ItemStack playerEntry(
            BalTopLeaderboardCache.Entry entry,
            EconomyService economyService
    ) {
        ItemStack item =
                new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta =
                item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(
                Bukkit.getOfflinePlayer(
                        entry.playerId()
                )
        );
        meta.displayName(
                component(
                        SECONDARY
                                + "#"
                                + entry.placement()
                                + " "
                                + BODY
                                + entry.displayName()
                )
        );
        meta.lore(
                List.of(
                        component(
                                BODY
                                        + "Balance: "
                                        + MONEY
                                        + economyService.format(
                                        entry.balanceCents()
                                )
                        ),
                        Component.empty(),
                        component(
                                BODY
                                        + "Click to view stats"
                        )
                )
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack selfHead(
            Player player,
            EconomyService economyService,
            BalTopLeaderboardCache.Entry entry
    ) {
        ItemStack item =
                new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta =
                item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(player);
        meta.displayName(
                component(
                        SECONDARY + "Your Balance"
                )
        );
        meta.lore(
                List.of(
                        component(
                                BODY
                                        + "Balance: "
                                        + MONEY
                                        + economyService.format(
                                        economyService.getBalanceCents(
                                                player.getUniqueId()
                                        )
                                )
                        ),
                        component(
                                BODY
                                        + "Rank: "
                                        + ACCENT
                                        + (entry == null
                                        ? "Unranked"
                                        : "#" + entry.placement())
                        ),
                        Component.empty(),
                        component(
                                BODY
                                        + "Click to view your stats"
                        )
                )
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack searchItem(
            SearchState search
    ) {
        List<String> lore =
                search == null
                        ? GuiSearchLore.inactive("players")
                        : GuiSearchLore.active(
                        search.displayLabel()
                );

        return toolbar(
                Material.OAK_SIGN,
                SECONDARY + "Search",
                lore
        );
    }

    private static ItemStack emptyItem(
            boolean searching
    ) {
        if (searching) {
            return toolbar(
                    Material.BARRIER,
                    "&cNo Results",
                    List.of(
                            BODY
                                    + "No matching players"
                    )
            );
        }

        return toolbar(
                Material.BARRIER,
                "&cNo Balances",
                List.of(
                        BODY
                                + "No balances recorded"
                )
        );
    }

    private static ItemStack toolbar(
            Material material,
            String name,
            List<String> lore
    ) {
        ItemStack item =
                new ItemStack(material);
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(component(name));
        meta.lore(
                lore.stream()
                        .map(BalTopGui::component)
                        .toList()
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);
        return item;
    }

    private static Component component(
            String input
    ) {
        return GuiText.component(input);
    }

    private record SearchState(
            String query,
            String displayLabel
    ) {
    }

    public static final class BalTopHolder
            implements InventoryHolder {

        private final Map<Integer, UUID> slotTargets =
                new LinkedHashMap<>();
        private final int page;
        private Inventory inventory;

        private BalTopHolder(
                int page
        ) {
            this.page = page;
        }

        public int page() {
            return page;
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
