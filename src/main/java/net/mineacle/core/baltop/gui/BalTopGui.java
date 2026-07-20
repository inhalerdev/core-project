package net.mineacle.core.baltop.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BalTopGui {

    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;

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

    private static final Map<UUID, SearchState> SEARCHES = new ConcurrentHashMap<>();

    private BalTopGui() {
    }

    public static void open(
            Core core,
            Player player,
            EconomyService economyService,
            int requestedPage
    ) {
        List<Map.Entry<UUID, Long>> leaderboard = snapshot(economyService);
        Map<UUID, Integer> placements = placements(leaderboard);
        SearchState search = SEARCHES.get(player.getUniqueId());
        List<Map.Entry<UUID, Long>> visibleEntries = filter(leaderboard, search);

        int totalPages = Math.max(
                1,
                (int) Math.ceil(visibleEntries.size() / (double) ENTRIES_PER_PAGE)
        );
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        BalTopHolder holder = new BalTopHolder(page);
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                "Balance Top (Page " + (page + 1) + ")"
        );
        holder.inventory = inventory;

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(visibleEntries.size(), start + ENTRIES_PER_PAGE);

        for (int index = start; index < end; index++) {
            Map.Entry<UUID, Long> entry = visibleEntries.get(index);
            UUID targetId = entry.getKey();
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            int slot = index - start;
            int placement = placements.getOrDefault(targetId, 0);

            inventory.setItem(
                    slot,
                    playerEntry(
                            target,
                            economyService.format(entry.getValue()),
                            placement
                    )
            );
            holder.slotTargets.put(slot, targetId);
        }

        if (visibleEntries.isEmpty()) {
            inventory.setItem(SLOT_EMPTY, emptyItem(search != null));
        }

        inventory.setItem(
                SLOT_PREVIOUS,
                navigationItem(
                        true,
                        page > 0,
                        Math.max(1, page)
                )
        );

        inventory.setItem(
                SLOT_PLAYER_HEAD,
                selfHead(
                        player,
                        economyService,
                        placements.getOrDefault(player.getUniqueId(), 0)
                )
        );

        inventory.setItem(
                SLOT_REFRESH,
                toolbar(
                        Material.PAPER,
                        "&dRefresh",
                        List.of("&#bbbbbbClick to refresh Balance Top")
                )
        );

        inventory.setItem(SLOT_SEARCH, searchItem(search));

        inventory.setItem(
                SLOT_NEXT,
                navigationItem(
                        false,
                        page < totalPages - 1,
                        Math.min(totalPages, page + 2)
                )
        );

        player.openInventory(inventory);
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

    public static boolean isBalTopInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof BalTopHolder;
    }

    public static BalTopHolder holder(Inventory inventory) {
        if (inventory == null || !(inventory.getHolder() instanceof BalTopHolder holder)) {
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

    public static void setSearch(Player player, String query, String displayLabel) {
        if (player == null || query == null || query.isBlank()) {
            clearSearch(player);
            return;
        }

        String label = displayLabel == null || displayLabel.isBlank()
                ? query.trim()
                : displayLabel.trim();

        SEARCHES.put(
                player.getUniqueId(),
                new SearchState(query.trim(), label)
        );
    }

    public static void clearSearch(Player player) {
        if (player != null) {
            SEARCHES.remove(player.getUniqueId());
        }
    }

    public static boolean hasSearch(Player player) {
        return player != null && SEARCHES.containsKey(player.getUniqueId());
    }

    public static void clearAllState() {
        SEARCHES.clear();
    }

    public static boolean hasMatches(Player player, EconomyService economyService) {
        SearchState search = SEARCHES.get(player.getUniqueId());

        if (search == null) {
            return !economyService.topBalances(1).isEmpty();
        }

        return !filter(snapshot(economyService), search).isEmpty();
    }

    private static List<Map.Entry<UUID, Long>> snapshot(EconomyService economyService) {
        List<Map.Entry<UUID, Long>> snapshot = new ArrayList<>();

        for (Map.Entry<UUID, Long> entry : economyService.topBalances(Integer.MAX_VALUE)) {
            snapshot.add(Map.entry(entry.getKey(), entry.getValue()));
        }

        return List.copyOf(snapshot);
    }

    private static Map<UUID, Integer> placements(List<Map.Entry<UUID, Long>> leaderboard) {
        Map<UUID, Integer> placements = new HashMap<>();

        for (int index = 0; index < leaderboard.size(); index++) {
            placements.put(leaderboard.get(index).getKey(), index + 1);
        }

        return placements;
    }

    private static List<Map.Entry<UUID, Long>> filter(
            List<Map.Entry<UUID, Long>> leaderboard,
            SearchState search
    ) {
        if (search == null || search.query().isBlank()) {
            return leaderboard;
        }

        String query = search.query().toLowerCase(Locale.ROOT);
        List<Map.Entry<UUID, Long>> filtered = new ArrayList<>();

        for (Map.Entry<UUID, Long> entry : leaderboard) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(entry.getKey());
            String username = target.getName() == null ? "" : target.getName();
            String displayName = DisplayNames.displayName(target);

            if (username.toLowerCase(Locale.ROOT).contains(query)
                    || displayName.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(entry);
            }
        }

        return filtered;
    }

    private static ItemStack playerEntry(
            OfflinePlayer target,
            String balance,
            int placement
    ) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(target);
        meta.setDisplayName(color("&#bbbbbb" + DisplayNames.displayName(target)));
        meta.setLore(List.of(
                color("&#bbbbbbBalance: &a" + balance),
                color("&#bbbbbbRank: &#ff88ff#" + placement),
                "",
                color("&#bbbbbbClick to view stats")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack selfHead(
            Player player,
            EconomyService economyService,
            int placement
    ) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(player);
        meta.setDisplayName(color("&#bbbbbb" + DisplayNames.displayName(player)));
        meta.setLore(List.of(
                color("&#bbbbbbBalance: &a"
                        + economyService.format(
                                economyService.getBalanceCents(player.getUniqueId())
                        )),
                color(
                        placement <= 0
                                ? "&#bbbbbbRank: &#ff88ffUnranked"
                                : "&#bbbbbbRank: &#ff88ff#" + placement
                ),
                "",
                color("&#bbbbbbClick to view your stats")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack searchItem(SearchState search) {
        List<String> lore = new ArrayList<>();

        if (search != null) {
            lore.add("&#bbbbbbCurrent: &#ff88ff" + search.displayLabel());
            lore.add("");
            lore.add("&#bbbbbbLeft-click to search again");
            lore.add("&#bbbbbbRight-click to clear search");
        } else {
            lore.add("&#bbbbbbClick to search Balance Top");
            lore.add("&#bbbbbbType a player name in chat");
        }

        return toolbar(Material.OAK_SIGN, "&dSearch", lore);
    }

    private static ItemStack emptyItem(boolean searching) {
        if (searching) {
            return toolbar(
                    Material.BARRIER,
                    "&cNo Results",
                    List.of(
                            "&#bbbbbbNo matching Balance Top players",
                            "&#bbbbbbUse the Search button to try again"
                    )
            );
        }

        return toolbar(
                Material.BARRIER,
                "&cNo Balances",
                List.of("&#bbbbbbNo balances have been recorded yet")
        );
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

        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(BalTopGui::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static String color(String input) {
        return TextColor.color(input);
    }

    private record SearchState(String query, String displayLabel) {
    }

    public static final class BalTopHolder implements InventoryHolder {

        private final Map<Integer, UUID> slotTargets = new LinkedHashMap<>();
        private final int page;
        private Inventory inventory;

        private BalTopHolder(int page) {
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
