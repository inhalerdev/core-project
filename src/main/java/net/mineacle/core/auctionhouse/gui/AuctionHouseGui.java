package net.mineacle.core.auctionhouse.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiSearchLore;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuctionHouseGui {

    public static final int SIZE = 54;

    private static final int[] BROWSE_TOOLBAR =
            CenteredToolbar.interiorSlots(SIZE, 5);
    private static final int[] OWN_TOOLBAR =
            CenteredToolbar.interiorSlots(SIZE, 4);

    private static final int SLOT_PREVIOUS =
            CenteredToolbar.previousSlot(SIZE);
    private static final int SLOT_FILTER = BROWSE_TOOLBAR[0];
    private static final int SLOT_WORTH = BROWSE_TOOLBAR[1];
    private static final int SLOT_REFRESH = BROWSE_TOOLBAR[2];
    private static final int SLOT_SEARCH = BROWSE_TOOLBAR[3];
    private static final int SLOT_OWN_ITEMS = BROWSE_TOOLBAR[4];
    private static final int SLOT_NEXT =
            CenteredToolbar.nextSlot(SIZE);

    private static final int SLOT_OWN_PREVIOUS =
            CenteredToolbar.previousSlot(SIZE);
    private static final int SLOT_OWN_BACK = OWN_TOOLBAR[0];
    private static final int SLOT_OWN_WORTH = OWN_TOOLBAR[1];
    private static final int SLOT_OWN_REFRESH = OWN_TOOLBAR[2];
    private static final int SLOT_OWN_LIST_ITEM = OWN_TOOLBAR[3];
    private static final int SLOT_OWN_NEXT =
            CenteredToolbar.nextSlot(SIZE);

    private static final int SLOT_CONFIRM_CANCEL = 11;
    private static final int SLOT_CONFIRM_ITEM = 13;
    private static final int SLOT_CONFIRM_ACTION = 15;

    private AuctionHouseGui() {
    }

    public static void openBrowse(
            Player player,
            AuctionHouseService service,
            int page,
            AuctionHouseService.SortMode sortMode,
            String query
    ) {
        AuctionHouseService.SortMode effectiveSort = sortMode == null
                ? AuctionHouseService.SortMode.LOWEST_PRICE
                : sortMode;
        String effectiveQuery = service.sanitizeSearchQuery(query);

        List<AuctionHouseListing> listings = service.search(
                effectiveQuery,
                effectiveSort
        );

        int maxPage = Math.max(
                0,
                (listings.size() - 1) / service.pageSize()
        );
        int effectivePage = Math.max(0, Math.min(page, maxPage));

        BrowseHolder holder = new BrowseHolder(
                effectivePage,
                effectiveSort,
                effectiveQuery
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                legacy("Auction (Page " + (effectivePage + 1) + ")")
        );
        holder.inventory = inventory;

        int start = effectivePage * service.pageSize();

        for (int slot = 0; slot < service.pageSize(); slot++) {
            int index = start + slot;

            if (index >= listings.size()) {
                break;
            }

            AuctionHouseListing listing = listings.get(index);

            inventory.setItem(
                    slot,
                    listingItem(
                            service,
                            listing,
                            "&dClick to buy"
                    )
            );
            holder.slotListings.put(slot, listing.id());
        }

        inventory.setItem(
                SLOT_PREVIOUS,
                navigationItem(
                        true,
                        effectivePage > 0,
                        Math.max(1, effectivePage)
                )
        );

        inventory.setItem(
                SLOT_FILTER,
                sortItem(effectiveSort)
        );

        inventory.setItem(
                SLOT_WORTH,
                item(
                        Material.AMETHYST_SHARD,
                        "&dWorth",
                        "&#bbbbbbClick to open Worth"
                )
        );

        inventory.setItem(
                SLOT_REFRESH,
                item(
                        Material.PAPER,
                        "&dRefresh",
                        "&#bbbbbbClick to refresh auctions"
                )
        );

        if (effectiveQuery.isBlank()) {
            inventory.setItem(
                    SLOT_SEARCH,
                    item(
                            Material.OAK_SIGN,
                            "&dSearch",
                            GuiSearchLore.inactive("auctions")
                                    .toArray(String[]::new)
                    )
            );
        } else {
            inventory.setItem(
                    SLOT_SEARCH,
                    item(
                            Material.OAK_SIGN,
                            "&dSearch",
                            GuiSearchLore.active(effectiveQuery).toArray(String[]::new)
                    )
            );
        }

        inventory.setItem(
                SLOT_OWN_ITEMS,
                item(
                        Material.CHEST,
                        "&dYour Items",
                        "&#bbbbbbClick to view your listings"
                )
        );

        inventory.setItem(
                SLOT_NEXT,
                navigationItem(
                        false,
                        (effectivePage + 1) * service.pageSize()
                                < listings.size(),
                        effectivePage + 2
                )
        );

        player.openInventory(inventory);
    }

    public static void openOwn(
            Player player,
            AuctionHouseService service
    ) {
        openOwn(player, service, 0);
    }

    public static void openOwn(
            Player player,
            AuctionHouseService service,
            int page
    ) {
        List<AuctionHouseListing> listings = service.ownerListings(
                player.getUniqueId()
        );
        int limit = service.listingLimit(player);

        int pageBasis = Math.max(
                listings.size(),
                Math.min(limit, service.pageSize())
        );
        int maxPage = Math.max(
                0,
                (pageBasis - 1) / service.pageSize()
        );
        int effectivePage = Math.max(0, Math.min(page, maxPage));

        OwnHolder holder = new OwnHolder(effectivePage);

        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                legacy(
                        "Auction -> Your Items (Page "
                                + (effectivePage + 1)
                                + ")"
                )
        );
        holder.inventory = inventory;

        int start = effectivePage * service.pageSize();

        for (int slot = 0; slot < service.pageSize(); slot++) {
            int index = start + slot;

            if (index < listings.size()) {
                AuctionHouseListing listing = listings.get(index);

                inventory.setItem(
                        slot,
                        listingItem(
                                service,
                                listing,
                                "&cClick to cancel listing"
                        )
                );
                holder.slotListings.put(slot, listing.id());
                continue;
            }

            if (index < limit) {
                inventory.setItem(
                        slot,
                        item(
                                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                                "&dAvailable Slot",
                                "&#bbbbbbUse &d/ah sell <price>",
                                "&#bbbbbbwhile holding an item"
                        )
                );
                continue;
            }

            inventory.setItem(
                    slot,
                    item(
                            Material.RED_STAINED_GLASS_PANE,
                            "&cLocked",
                            "&#bbbbbbUpgrade to &dMineacle+",
                            "&#bbbbbbfor more auction slots"
                    )
            );
        }

        inventory.setItem(
                SLOT_OWN_PREVIOUS,
                navigationItem(
                        true,
                        effectivePage > 0,
                        Math.max(1, effectivePage)
                )
        );

        inventory.setItem(
                SLOT_OWN_BACK,
                item(
                        Material.ARROW,
                        "&dBack",
                        "&#bbbbbbClick to return to auctions"
                )
        );

        inventory.setItem(
                SLOT_OWN_WORTH,
                item(
                        Material.AMETHYST_SHARD,
                        "&dWorth",
                        "&#bbbbbbClick to open Worth"
                )
        );

        inventory.setItem(
                SLOT_OWN_REFRESH,
                item(
                        Material.PAPER,
                        "&dRefresh",
                        "&#bbbbbbClick to refresh your listings"
                )
        );

        inventory.setItem(
                SLOT_OWN_LIST_ITEM,
                item(
                        Material.OAK_SIGN,
                        "&dList Item",
                        "&#bbbbbbHold an item and use",
                        "&d/ah sell <price>"
                )
        );

        inventory.setItem(
                SLOT_OWN_NEXT,
                navigationItem(
                        false,
                        (effectivePage + 1) * service.pageSize()
                                < listings.size(),
                        effectivePage + 2
                )
        );

        player.openInventory(inventory);
    }

    public static void openConfirmBuy(
            Player player,
            AuctionHouseService service,
            AuctionHouseListing listing,
            int returnPage,
            AuctionHouseService.SortMode returnSort,
            String returnQuery
    ) {
        ConfirmBuyHolder holder = new ConfirmBuyHolder(
                listing.id(),
                returnPage,
                returnSort,
                returnQuery
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                legacy("Confirm Purchase")
        );
        holder.inventory = inventory;

        inventory.setItem(
                SLOT_CONFIRM_CANCEL,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        "&#bbbbbbClick to return"
                )
        );

        inventory.setItem(
                SLOT_CONFIRM_ITEM,
                listingItem(service, listing, null)
        );

        inventory.setItem(
                SLOT_CONFIRM_ACTION,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aConfirm",
                        "&#bbbbbbBuy this item for &a"
                                + service.format(listing.priceCents())
                )
        );

        player.openInventory(inventory);
    }

    public static void openConfirmCancel(
            Player player,
            AuctionHouseService service,
            AuctionHouseListing listing,
            int returnPage
    ) {
        ConfirmCancelHolder holder = new ConfirmCancelHolder(
                listing.id(),
                returnPage
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                legacy("Confirm Cancellation")
        );
        holder.inventory = inventory;

        inventory.setItem(
                SLOT_CONFIRM_CANCEL,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        "&#bbbbbbClick to return"
                )
        );

        inventory.setItem(
                SLOT_CONFIRM_ITEM,
                listingItem(service, listing, null)
        );

        inventory.setItem(
                SLOT_CONFIRM_ACTION,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aConfirm",
                        "&#bbbbbbReturn this item to your inventory"
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
            return item(
                    Material.GRAY_STAINED_GLASS_PANE,
                    previous
                            ? "&#bbbbbbPrevious Page"
                            : "&#bbbbbbNext Page",
                    "&#bbbbbbNo "
                            + (previous ? "previous" : "next")
                            + " page"
            );
        }

        return item(
                Material.ARROW,
                previous ? "&dPrevious Page" : "&dNext Page",
                "&#bbbbbbPage &#ff88ff" + targetPage
        );
    }

    private static ItemStack listingItem(
            AuctionHouseService service,
            AuctionHouseListing listing,
            String actionLine
    ) {
        ItemStack item = listing.item();
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore())
                : new ArrayList<>();

        if (!lore.isEmpty()) {
            lore.add(legacy(""));
        }

        lore.add(
                legacy(
                        "&#bbbbbbPrice: &a"
                                + service.format(listing.priceCents())
                )
        );
        lore.add(
                legacy(
                        "&#bbbbbbSeller: &#bbbbbb"
                                + service.sellerDisplayName(listing)
                )
        );

        if (actionLine != null && !actionLine.isBlank()) {
            lore.add(legacy(""));
            lore.add(legacy(actionLine));
        }

        meta.lore(noItalic(lore));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack sortItem(
            AuctionHouseService.SortMode current
    ) {
        List<String> lore = new ArrayList<>();
        lore.add(
                "&#bbbbbbCurrent: &#ff88ff"
                        + current.label()
        );
        lore.add("");

        for (AuctionHouseService.SortMode mode
                : AuctionHouseService.SortMode.values()) {
            lore.add(
                    (mode == current
                            ? "&#ff88ff"
                            : "&#bbbbbb")
                            + mode.label()
            );
        }

        lore.add("");
        lore.add("&#bbbbbbClick to change sort");

        return item(
                Material.ANVIL,
                "&dSort",
                lore.toArray(String[]::new)
        );
    }

    public static ItemStack item(
            Material material,
            String name,
            String... loreLines
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
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_DYE
        );

        item.setItemMeta(meta);
        return item;
    }

    public static int previousSlot() {
        return SLOT_PREVIOUS;
    }

    public static int sortSlot() {
        return SLOT_FILTER;
    }

    /**
     * Compatibility alias for callers compiled against the former name.
     */
    public static int filterSlot() {
        return sortSlot();
    }

    public static int worthSlot() {
        return SLOT_WORTH;
    }

    public static int refreshSlot() {
        return SLOT_REFRESH;
    }

    public static int searchSlot() {
        return SLOT_SEARCH;
    }

    public static int ownItemsSlot() {
        return SLOT_OWN_ITEMS;
    }

    public static int nextSlot() {
        return SLOT_NEXT;
    }

    public static int ownPreviousSlot() {
        return SLOT_OWN_PREVIOUS;
    }

    public static int ownBackSlot() {
        return SLOT_OWN_BACK;
    }

    public static int ownWorthSlot() {
        return SLOT_OWN_WORTH;
    }

    public static int ownRefreshSlot() {
        return SLOT_OWN_REFRESH;
    }

    public static int ownListItemSlot() {
        return SLOT_OWN_LIST_ITEM;
    }

    public static int ownNextSlot() {
        return SLOT_OWN_NEXT;
    }

    public static int confirmCancelSlot() {
        return SLOT_CONFIRM_CANCEL;
    }

    public static int confirmActionSlot() {
        return SLOT_CONFIRM_ACTION;
    }

    private static List<Component> noItalic(List<Component> input) {
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
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(text == null ? "" : text)
                )
                .decoration(TextDecoration.ITALIC, false);
    }

    public static final class BrowseHolder implements InventoryHolder {

        private final Map<Integer, UUID> slotListings = new HashMap<>();
        private final int page;
        private final AuctionHouseService.SortMode sortMode;
        private final String query;
        private Inventory inventory;

        private BrowseHolder(
                int page,
                AuctionHouseService.SortMode sortMode,
                String query
        ) {
            this.page = page;
            this.sortMode = sortMode;
            this.query = query == null ? "" : query;
        }

        public UUID listingAt(int slot) {
            return slotListings.get(slot);
        }

        public int page() {
            return page;
        }

        public AuctionHouseService.SortMode sortMode() {
            return sortMode;
        }

        public String query() {
            return query;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class OwnHolder implements InventoryHolder {

        private final Map<Integer, UUID> slotListings = new HashMap<>();
        private final int page;
        private Inventory inventory;

        private OwnHolder(int page) {
            this.page = page;
        }

        public UUID listingAt(int slot) {
            return slotListings.get(slot);
        }

        public int page() {
            return page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class ConfirmBuyHolder implements InventoryHolder {

        private final UUID listingId;
        private final int returnPage;
        private final AuctionHouseService.SortMode returnSort;
        private final String returnQuery;
        private Inventory inventory;

        private ConfirmBuyHolder(
                UUID listingId,
                int returnPage,
                AuctionHouseService.SortMode returnSort,
                String returnQuery
        ) {
            this.listingId = listingId;
            this.returnPage = returnPage;
            this.returnSort = returnSort == null
                    ? AuctionHouseService.SortMode.LOWEST_PRICE
                    : returnSort;
            this.returnQuery = returnQuery == null ? "" : returnQuery;
        }

        public UUID listingId() {
            return listingId;
        }

        public int returnPage() {
            return returnPage;
        }

        public AuctionHouseService.SortMode returnSort() {
            return returnSort;
        }

        public String returnQuery() {
            return returnQuery;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class ConfirmCancelHolder implements InventoryHolder {

        private final UUID listingId;
        private final int returnPage;
        private Inventory inventory;

        private ConfirmCancelHolder(
                UUID listingId,
                int returnPage
        ) {
            this.listingId = listingId;
            this.returnPage = returnPage;
        }

        public UUID listingId() {
            return listingId;
        }

        public int returnPage() {
            return returnPage;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
