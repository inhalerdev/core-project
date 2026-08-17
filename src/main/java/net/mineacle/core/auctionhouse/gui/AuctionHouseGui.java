package net.mineacle.core.auctionhouse.gui;

import net.kyori.adventure.text.Component;
import net.mineacle.core.auctionhouse.model.AuctionHistoryEntry;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiText;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuctionHouseGui {

    public static final int SIZE = 54;

    private static final int[] BROWSE_TOOLBAR =
            CenteredToolbar
                    .interiorSlots(
                            SIZE,
                            5
                    );
    private static final int[] OWN_TOOLBAR =
            CenteredToolbar
                    .interiorSlotsCenteredOn(
                            SIZE,
                            4,
                            2
                    );
    private static final int[] HISTORY_TOOLBAR =
            CenteredToolbar
                    .interiorSlotsCenteredOn(
                            SIZE,
                            2,
                            1
                    );

    private static final int SLOT_PREVIOUS =
            CenteredToolbar
                    .previousSlot(SIZE);
    private static final int SLOT_SORT =
            BROWSE_TOOLBAR[0];
    private static final int SLOT_FILTER =
            BROWSE_TOOLBAR[1];
    private static final int SLOT_REFRESH =
            BROWSE_TOOLBAR[2];
    private static final int SLOT_SEARCH =
            BROWSE_TOOLBAR[3];
    private static final int SLOT_OWN_ITEMS =
            BROWSE_TOOLBAR[4];
    private static final int SLOT_NEXT =
            CenteredToolbar
                    .nextSlot(SIZE);

    private static final int SLOT_OWN_PREVIOUS =
            CenteredToolbar
                    .previousSlot(SIZE);
    private static final int SLOT_OWN_BACK =
            OWN_TOOLBAR[0];
    private static final int SLOT_OWN_HISTORY =
            OWN_TOOLBAR[1];
    private static final int SLOT_OWN_REFRESH =
            OWN_TOOLBAR[2];
    private static final int SLOT_OWN_LIST_ITEM =
            OWN_TOOLBAR[3];
    private static final int SLOT_OWN_NEXT =
            CenteredToolbar
                    .nextSlot(SIZE);

    private static final int SLOT_HISTORY_PREVIOUS =
            CenteredToolbar
                    .previousSlot(SIZE);
    private static final int SLOT_HISTORY_BACK =
            HISTORY_TOOLBAR[0];
    private static final int SLOT_HISTORY_REFRESH =
            HISTORY_TOOLBAR[1];
    private static final int SLOT_HISTORY_NEXT =
            CenteredToolbar
                    .nextSlot(SIZE);

    private static final int SLOT_CONFIRM_BACK = 11;
    private static final int SLOT_CONFIRM_ITEM = 13;
    private static final int SLOT_CONFIRM_ACTION = 15;

    private AuctionHouseGui() {
    }

    public static void openBrowse(
            Player player,
            AuctionHouseService service,
            int page,
            AuctionHouseService.SortMode sortMode,
            AuctionHouseService.FilterMode filterMode,
            String query
    ) {
        AuctionHouseService.SortMode effectiveSort =
                sortMode == null
                        ? service.defaultSort()
                        : sortMode;
        AuctionHouseService.FilterMode effectiveFilter =
                filterMode == null
                        ? AuctionHouseService
                        .FilterMode.ALL
                        : filterMode;
        String effectiveQuery =
                service.sanitizeSearchQuery(
                        query
                );

        List<AuctionHouseListing> listings =
                service.search(
                        effectiveQuery,
                        effectiveSort,
                        effectiveFilter
                );

        int maxPage =
                Math.max(
                        0,
                        (listings.size() - 1)
                                / service.pageSize()
                );
        int effectivePage =
                Math.clamp(
                        page,
                        0,
                        maxPage
                );

        BrowseHolder holder =
                new BrowseHolder(
                        effectivePage,
                        effectiveSort,
                        effectiveFilter,
                        effectiveQuery
                );

        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                "Auction House (Page "
                                        + (
                                        effectivePage
                                                + 1
                                )
                                        + "/"
                                        + (
                                        maxPage
                                                + 1
                                )
                                        + ")"
                        )
                );
        holder.inventory = inventory;

        int start =
                effectivePage
                        * service.pageSize();

        for (int slot = 0;
             slot < service.pageSize();
             slot++) {
            int index =
                    start + slot;

            if (index
                    >= listings.size()) {
                break;
            }

            AuctionHouseListing listing =
                    listings.get(index);

            inventory.setItem(
                    slot,
                    listingItem(
                            service,
                            listing,
                            ListingContext.BROWSE
                    )
            );
            holder.slotListings.put(
                    slot,
                    listing.id()
            );
        }

        if (listings.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.GRAY_DYE,
                            "&#bbbbbbNo Listings",
                            effectiveQuery.isBlank()
                                    ? "&#bbbbbbNo active listings"
                                    : "&#bbbbbbNo results for &#D0AFFF"
                                    + effectiveQuery
                    )
            );
        }

        if (effectivePage > 0) {
            inventory.setItem(
                    SLOT_PREVIOUS,
                    navigationItem(
                            true,
                            effectivePage
                    )
            );
        }

        inventory.setItem(
                SLOT_SORT,
                sortItem(effectiveSort)
        );
        inventory.setItem(
                SLOT_FILTER,
                filterItem(
                        effectiveFilter
                )
        );
        inventory.setItem(
                SLOT_REFRESH,
                item(
                        Material.EMERALD,
                        "&#B078FFRefresh",
                        "&#bbbbbbReload current results"
                )
        );
        inventory.setItem(
                SLOT_SEARCH,
                searchItem(
                        effectiveQuery
                )
        );
        inventory.setItem(
                SLOT_OWN_ITEMS,
                ownListingsButton(
                        player,
                        service
                )
        );

        if ((effectivePage + 1)
                * service.pageSize()
                < listings.size()) {
            inventory.setItem(
                    SLOT_NEXT,
                    navigationItem(
                            false,
                            effectivePage + 2
                    )
            );
        }

        player.openInventory(
                inventory
        );
    }

    public static void openOwn(
            Player player,
            AuctionHouseService service,
            int page
    ) {
        List<AuctionHouseListing> listings =
                service.ownerListings(
                        player.getUniqueId()
                );

        int maxPage =
                Math.max(
                        0,
                        (listings.size() - 1)
                                / service.pageSize()
                );
        int effectivePage =
                Math.clamp(
                        page,
                        0,
                        maxPage
                );

        OwnHolder holder =
                new OwnHolder(
                        effectivePage
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                "Your Listings (Page "
                                        + (
                                        effectivePage
                                                + 1
                                )
                                        + "/"
                                        + (
                                        maxPage
                                                + 1
                                )
                                        + ")"
                        )
                );
        holder.inventory = inventory;

        int start =
                effectivePage
                        * service.pageSize();
        int pageListings =
                Math.clamp(
                        listings.size() - start,
                        0,
                        service.pageSize()
                );
        int listingLimit =
                service.listingLimit(
                        player
                );
        int visibleListingSlots =
                Math.clamp(
                        listingLimit,
                        pageListings,
                        service.pageSize()
                );

        for (int slot = 0;
             slot < service.pageSize();
             slot++) {
            int index =
                    start + slot;

            if (index
                    >= listings.size()) {
                break;
            }

            AuctionHouseListing listing =
                    listings.get(index);

            inventory.setItem(
                    slot,
                    listingItem(
                            service,
                            listing,
                            ListingContext.OWN
                    )
            );
            holder.slotListings.put(
                    slot,
                    listing.id()
            );
        }

        for (int slot = visibleListingSlots;
             slot < service.pageSize();
             slot++) {
            inventory.setItem(
                    slot,
                    lockedListingSlot(service)
            );
        }

        if (listings.isEmpty()) {
            int noListingsSlot =
                    listingLimit <= 18
                            ? 13
                            : 22;

            inventory.setItem(
                    noListingsSlot,
                    item(
                            Material.GRAY_DYE,
                            "&#bbbbbbNo Listings",
                            "&#bbbbbbHold an item and use List Held Item"
                    )
            );
        }

        if (effectivePage > 0) {
            inventory.setItem(
                    SLOT_OWN_PREVIOUS,
                    navigationItem(
                            true,
                            effectivePage
                    )
            );
        }

        inventory.setItem(
                SLOT_OWN_BACK,
                item(
                        Material.ARROW,
                        "&#B078FFBack",
                        "&#bbbbbbReturn to Auction House"
                )
        );
        inventory.setItem(
                SLOT_OWN_HISTORY,
                transactionHistoryButton()
        );
        inventory.setItem(
                SLOT_OWN_REFRESH,
                item(
                        Material.EMERALD,
                        "&#B078FFRefresh",
                        "&#bbbbbbReload your listings"
                )
        );
        inventory.setItem(
                SLOT_OWN_LIST_ITEM,
                listHeldItemButton(
                        player,
                        service
                )
        );

        if ((effectivePage + 1)
                * service.pageSize()
                < listings.size()) {
            inventory.setItem(
                    SLOT_OWN_NEXT,
                    navigationItem(
                            false,
                            effectivePage + 2
                    )
            );
        }

        player.openInventory(
                inventory
        );
    }

    public static void openHistory(
            Player player,
            AuctionHouseService service,
            int page
    ) {
        List<AuctionHistoryEntry> entries =
                service.history(
                        player.getUniqueId()
                );

        int maxPage =
                Math.max(
                        0,
                        (entries.size() - 1)
                                / service.pageSize()
                );
        int effectivePage =
                Math.clamp(
                        page,
                        0,
                        maxPage
                );

        HistoryHolder holder =
                new HistoryHolder(
                        effectivePage
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                "Transaction History (Page "
                                        + (
                                        effectivePage
                                                + 1
                                )
                                        + "/"
                                        + (
                                        maxPage
                                                + 1
                                )
                                        + ")"
                        )
                );
        holder.inventory = inventory;

        int start =
                effectivePage
                        * service.pageSize();

        for (int slot = 0;
             slot < service.pageSize();
             slot++) {
            int index =
                    start + slot;

            if (index >= entries.size()) {
                break;
            }

            inventory.setItem(
                    slot,
                    historyEntryItem(
                            service,
                            entries.get(index)
                    )
            );
        }

        if (entries.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.WRITABLE_BOOK,
                            "&#bbbbbbNo Transactions",
                            "&#bbbbbbYour Auction House activity will appear here"
                    )
            );
        }

        if (effectivePage > 0) {
            inventory.setItem(
                    SLOT_HISTORY_PREVIOUS,
                    navigationItem(
                            true,
                            effectivePage
                    )
            );
        }

        inventory.setItem(
                SLOT_HISTORY_BACK,
                item(
                        Material.ARROW,
                        "&#B078FFBack",
                        "&#bbbbbbReturn to Your Listings"
                )
        );
        inventory.setItem(
                SLOT_HISTORY_REFRESH,
                item(
                        Material.EMERALD,
                        "&#B078FFRefresh",
                        "&#bbbbbbReload transaction history"
                )
        );

        if ((effectivePage + 1)
                * service.pageSize()
                < entries.size()) {
            inventory.setItem(
                    SLOT_HISTORY_NEXT,
                    navigationItem(
                            false,
                            effectivePage + 2
                    )
            );
        }

        player.openInventory(
                inventory
        );
    }

    public static void openConfirmBuy(
            Player player,
            AuctionHouseService service,
            AuctionHouseListing listing,
            int returnPage,
            AuctionHouseService.SortMode returnSort,
            AuctionHouseService.FilterMode returnFilter,
            String returnQuery
    ) {
        ConfirmBuyHolder holder =
                new ConfirmBuyHolder(
                        listing.id(),
                        returnPage,
                        returnSort,
                        returnFilter,
                        returnQuery
                );

        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        27,
                        GuiText.title(
                                "Confirm Purchase"
                        )
                );
        holder.inventory = inventory;

        inventory.setItem(
                SLOT_CONFIRM_BACK,
                item(
                        Material.ARROW,
                        "&#B078FFBack",
                        "&#bbbbbbReturn without buying"
                )
        );
        inventory.setItem(
                SLOT_CONFIRM_ITEM,
                listingItem(
                        service,
                        listing,
                        ListingContext.CONFIRM
                )
        );
        inventory.setItem(
                SLOT_CONFIRM_ACTION,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aBuy",
                        "&#bbbbbbPrice: &#11fc7b"
                                + service.format(
                                listing.priceCents()
                        ),
                        "&#bbbbbbClick to confirm"
                )
        );

        player.openInventory(
                inventory
        );
    }

    public static void openConfirmCancel(
            Player player,
            AuctionHouseService service,
            AuctionHouseListing listing,
            int returnPage
    ) {
        boolean expired =
                service.isExpired(
                        listing
                );

        ConfirmCancelHolder holder =
                new ConfirmCancelHolder(
                        listing.id(),
                        returnPage
                );

        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        27,
                        GuiText.title(
                                expired
                                        ? "Reclaim Listing"
                                        : "Cancel Listing"
                        )
                );
        holder.inventory = inventory;

        inventory.setItem(
                SLOT_CONFIRM_BACK,
                item(
                        Material.ARROW,
                        "&#B078FFBack",
                        "&#bbbbbbReturn without changes"
                )
        );
        inventory.setItem(
                SLOT_CONFIRM_ITEM,
                listingItem(
                        service,
                        listing,
                        ListingContext.CONFIRM
                )
        );
        inventory.setItem(
                SLOT_CONFIRM_ACTION,
                item(
                        expired
                                ? Material
                                .LIME_STAINED_GLASS_PANE
                                : Material
                                .RED_STAINED_GLASS_PANE,
                        expired
                                ? "&aReclaim"
                                : "&cCancel Listing",
                        expired
                                ? "&#bbbbbbReturn this item to your inventory"
                                : "&#bbbbbbRemove this listing",
                        "&#bbbbbbItem returns to your inventory"
                )
        );

        player.openInventory(
                inventory
        );
    }

    private static ItemStack listingItem(
            AuctionHouseService service,
            AuctionHouseListing listing,
            ListingContext context
    ) {
        ItemStack item =
                listing.item();
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<Component> existingLore =
                meta.lore();
        List<Component> lore =
                existingLore == null
                        ? new ArrayList<>()
                        : new ArrayList<>(
                        existingLore
                );

        if (!lore.isEmpty()) {
            lore.add(
                    Component.empty()
            );
        }

        lore.add(
                GuiText.component(
                        "&#bbbbbbPrice: &#11fc7b"
                                + service.format(
                                listing.priceCents()
                        )
                )
        );

        if (listing.amount() > 1) {
            lore.add(
                    GuiText.component(
                            "&#bbbbbbEach: &#11fc7b"
                                    + service.format(
                                    service.unitPriceCents(
                                            listing
                                    )
                            )
                    )
            );
        }

        long worth =
                service.worthCents(
                        listing
                );

        if (worth > 0L) {
            lore.add(
                    GuiText.component(
                            "&#bbbbbbWorth: &#11fc7b"
                                    + service.format(
                                    worth
                            )
                    )
            );
        }

        if (context
                != ListingContext.OWN) {
            lore.add(
                    GuiText.component(
                            "&#bbbbbbSeller: &#B078FF"
                                    + service
                                    .sellerDisplayName(
                                            listing
                                    )
                    )
            );
        }

        boolean expired =
                service.isExpired(
                        listing
                );
        boolean previewShulker =
                item.getType()
                        .name()
                        .endsWith(
                                "SHULKER_BOX"
                        );

        lore.add(
                GuiText.component(
                        expired
                                ? "&cExpired"
                                : "&#bbbbbbExpires: &#D0AFFF"
                                + service.expiryText(
                                listing
                        )
                )
        );

        if (context
                == ListingContext.BROWSE) {
            lore.add(
                    Component.empty()
            );
            lore.add(
                    GuiText.component(
                            previewShulker
                                    ? "&#bbbbbbLeft-click to review"
                                    : "&#bbbbbbClick to review"
                    )
            );

            if (previewShulker) {
                lore.add(
                        GuiText.component(
                                "&#D0AFFFRight-click to preview"
                        )
                );
            }

            if (service
                    .quickBuyEnabled()) {
                lore.add(
                        GuiText.component(
                                previewShulker
                                        ? "&#D0AFFFShift-left-click to buy now"
                                        : "&#D0AFFFShift-click to buy now"
                        )
                );
            }
        } else if (context
                == ListingContext.OWN) {
            lore.add(
                    Component.empty()
            );
            lore.add(
                    GuiText.component(
                            expired
                                    ? "&aLeft-click to reclaim"
                                    : "&cLeft-click to cancel"
                    )
            );

            if (previewShulker) {
                lore.add(
                        GuiText.component(
                                "&#D0AFFFRight-click to preview"
                        )
                );
            }

            lore.add(
                    GuiText.component(
                            expired
                                    ? "&#D0AFFFShift-left-click to reclaim now"
                                    : "&#D0AFFFShift-left-click to cancel now"
                    )
            );
        }

        meta.lore(
                List.copyOf(lore)
        );
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack lockedListingSlot(
            AuctionHouseService service
    ) {
        int normal =
                service.defaultListingLimit();
        int expanded =
                service.elevatedListingLimit();
        int extra =
                Math.max(
                        0,
                        expanded - normal
                );

        return item(
                Material.PURPLE_STAINED_GLASS_PANE,
                "&#B078FFUnlock "
                        + expanded
                        + " Auction Slots",
                "&#bbbbbbYou're limited to &#D0AFFF"
                        + normal
                        + " &#bbbbbbactive listings",
                "&#bbbbbbMineacle+ unlocks &#B078FF"
                        + expanded
                        + " &#bbbbbbslots",
                "&#D0AFFF"
                        + extra
                        + " more listings at once",
                "",
                "&#B078FFClick to view Mineacle+"
        );
    }

    private static ItemStack sortItem(
            AuctionHouseService.SortMode current
    ) {
        List<String> lore =
                new ArrayList<>();

        lore.add(
                "&#bbbbbbCurrent: &#D0AFFF"
                        + current.label()
        );
        lore.add("");

        for (AuctionHouseService.SortMode mode
                : AuctionHouseService
                .SortMode.values()) {
            lore.add(
                    (
                            mode == current
                                    ? "&#D0AFFF"
                                    : "&#bbbbbb"
                    )
                            + mode.label()
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

    private static ItemStack filterItem(
            AuctionHouseService.FilterMode current
    ) {
        List<String> lore =
                new ArrayList<>();

        lore.add(
                "&#bbbbbbCurrent: &#D0AFFF"
                        + current.label()
        );
        lore.add("");

        for (AuctionHouseService.FilterMode mode
                : AuctionHouseService
                .FilterMode.values()) {
            lore.add(
                    (
                            mode == current
                                    ? "&#D0AFFF"
                                    : "&#bbbbbb"
                    )
                            + mode.label()
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
                Material.HOPPER,
                "&#B078FFFilter",
                lore.toArray(
                        String[]::new
                )
        );
    }

    private static ItemStack searchItem(
            String query
    ) {
        if (query == null
                || query.isBlank()) {
            return item(
                    Material.OAK_SIGN,
                    "&#B078FFSearch",
                    "&#bbbbbbClick to search",
                    "&#bbbbbbOr use &#D0AFFF/ah <item>"
            );
        }

        return item(
                Material.OAK_SIGN,
                "&#B078FFSearch",
                "&#bbbbbbCurrent: &#D0AFFF"
                        + query,
                "",
                "&#bbbbbbClick to replace",
                "&#bbbbbbRight-click to clear"
        );
    }

    private static ItemStack ownListingsButton(
            Player player,
            AuctionHouseService service
    ) {
        int occupied =
                service.occupiedListingCount(
                        player.getUniqueId()
                );
        int active =
                service.activeListingCount(
                        player.getUniqueId()
                );
        int expired =
                service.expiredListingCount(
                        player.getUniqueId()
                );
        int limit =
                service.listingLimit(
                        player
                );

        List<String> lore =
                new ArrayList<>();
        lore.add(
                "&#bbbbbbSlots: &#D0AFFF"
                        + occupied
                        + "/"
                        + limit
        );
        lore.add(
                "&#bbbbbbActive: &#D0AFFF"
                        + active
        );

        if (limit
                < service.elevatedListingLimit()) {
            lore.add(
                    "&#B078FFMineacle+ unlocks "
                            + service.elevatedListingLimit()
                            + " Auction slots"
            );
        }

        if (expired > 0) {
            lore.add(
                    "&#bbbbbbExpired: &c"
                            + expired
            );
        }

        lore.add("");
        lore.add(
                "&#bbbbbbClick to manage listings"
        );

        return ownListingsHead(
                player,
                lore.toArray(
                        String[]::new
                )
        );
    }

    private static ItemStack transactionHistoryButton() {
        return item(
                Material.WRITABLE_BOOK,
                "&#B078FFTransaction History",
                "&#bbbbbbPurchases, sales and listing activity",
                "",
                "&#bbbbbbClick to view"
        );
    }

    private static ItemStack listHeldItemButton(
            Player player,
            AuctionHouseService service
    ) {
        int occupied =
                service.occupiedListingCount(
                        player.getUniqueId()
                );
        int limit =
                service.listingLimit(
                        player
                );

        if (!service.canList(player)) {
            return item(
                    Material.BARRIER,
                    "&cListing Unavailable",
                    "&#bbbbbbYou cannot list items"
            );
        }

        if (service.listingSlotsFull(player)) {
            if (limit
                    <= service.defaultListingLimit()
                    && service.elevatedListingLimit()
                    > limit) {
                return item(
                        Material.BARRIER,
                        "&cAuction Slots Full",
                        "&#bbbbbbYou've reached your &#D0AFFF"
                                + limit
                                + "&#bbbbbb-slot limit",
                        "",
                        "&#B078FFMineacle+ unlocks "
                                + service.elevatedListingLimit()
                                + " Auction slots",
                        "&#D0AFFFList "
                                + (
                                service.elevatedListingLimit()
                                        - limit
                        )
                                + " more items at once",
                        "",
                        "&#bbbbbbClick a locked slot to upgrade"
                );
            }

            return item(
                    Material.BARRIER,
                    "&cAuction Slots Full",
                    "&#bbbbbbSlots: &c"
                            + occupied
                            + "/"
                            + limit,
                    "",
                    "&#bbbbbbCancel or reclaim a listing first"
            );
        }

        return item(
                Material.OAK_SIGN,
                "&#B078FFList Held Item",
                "&#bbbbbbSlots: &#D0AFFF"
                        + occupied
                        + "/"
                        + limit,
                "",
                "&#bbbbbbClick: List held stack",
                "&#bbbbbbShift-click: List one item"
        );
    }

    private static ItemStack historyEntryItem(
            AuctionHouseService service,
            AuctionHistoryEntry entry
    ) {
        String typeColor =
                switch (entry.type()) {
                    case SOLD -> "&#11fc7b";
                    case PURCHASED, LISTED -> "&#B078FF";
                    case RECLAIMED -> "&a";
                    case CANCELLED -> "&c";
                };

        List<String> lore =
                new ArrayList<>();

        lore.add(
                "&#bbbbbbType: "
                        + typeColor
                        + entry.type().label()
        );
        lore.add(
                "&#bbbbbbAmount: &#D0AFFF"
                        + entry.amount()
        );

        switch (entry.type()) {
            case SOLD ->
                    lore.add(
                            "&#bbbbbbReceived: &#11fc7b+"
                                    + service.format(
                                    entry.priceCents()
                            )
                    );
            case PURCHASED ->
                    lore.add(
                            "&#bbbbbbPaid: &#11fc7b"
                                    + service.format(
                                    entry.priceCents()
                            )
                    );
            case LISTED, CANCELLED, RECLAIMED ->
                    lore.add(
                            "&#bbbbbbListing Price: &#11fc7b"
                                    + service.format(
                                    entry.priceCents()
                            )
                    );
        }

        if (entry.counterpartId() != null) {
            lore.add(
                    "&#bbbbbb"
                            + (
                            entry.type()
                                    == AuctionHistoryEntry.Type.SOLD
                                    ? "Buyer"
                                    : "Seller"
                    )
                            + ": &#B078FF"
                            + service.historyCounterpartName(
                            entry
                    )
            );
        }

        lore.add(
                "&#bbbbbbDate: &#D0AFFF"
                        + service.historyTime(
                        entry.timestamp()
                )
        );
        lore.add(
                "&#bbbbbbID: &#D0AFFF"
                        + entry.transactionId()
                        .toString()
                        .substring(
                                0,
                                8
                        )
        );

        return item(
                entry.material(),
                "&#B078FF"
                        + entry.itemName(),
                lore.toArray(
                        String[]::new
                )
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

    public static ItemStack item(
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
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_DYE
        );

        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack ownListingsHead(
            Player player,
            String... loreLines
    ) {
        ItemStack item =
                item(
                        Material.PLAYER_HEAD,
                        "&#B078FFYour Listings",
                        loreLines
                );
        ItemMeta meta =
                item.getItemMeta();

        if (meta
                instanceof SkullMeta skull) {
            skull.setOwningPlayer(
                    player
            );
            item.setItemMeta(skull);
        }

        return item;
    }

    public static int previousSlot() {
        return SLOT_PREVIOUS;
    }

    public static int sortSlot() {
        return SLOT_SORT;
    }

    public static int filterSlot() {
        return SLOT_FILTER;
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

    public static int ownHistorySlot() {
        return SLOT_OWN_HISTORY;
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

    public static int historyPreviousSlot() {
        return SLOT_HISTORY_PREVIOUS;
    }

    public static int historyBackSlot() {
        return SLOT_HISTORY_BACK;
    }

    public static int historyRefreshSlot() {
        return SLOT_HISTORY_REFRESH;
    }

    public static int historyNextSlot() {
        return SLOT_HISTORY_NEXT;
    }

    public static int confirmBackSlot() {
        return SLOT_CONFIRM_BACK;
    }

    public static int confirmActionSlot() {
        return SLOT_CONFIRM_ACTION;
    }

    private enum ListingContext {
        BROWSE,
        OWN,
        CONFIRM
    }

    public static final class BrowseHolder
            implements InventoryHolder {

        private final Map<Integer, UUID>
                slotListings =
                new HashMap<>();
        private final int page;
        private final AuctionHouseService.SortMode
                sortMode;
        private final AuctionHouseService.FilterMode
                filterMode;
        private final String query;
        private Inventory inventory;

        private BrowseHolder(
                int page,
                AuctionHouseService.SortMode
                        sortMode,
                AuctionHouseService.FilterMode
                        filterMode,
                String query
        ) {
            this.page = page;
            this.sortMode = sortMode;
            this.filterMode = filterMode;
            this.query =
                    query == null
                            ? ""
                            : query;
        }

        public UUID listingAt(
                int slot
        ) {
            return slotListings.get(
                    slot
            );
        }

        public int page() {
            return page;
        }

        public AuctionHouseService.SortMode
        sortMode() {
            return sortMode;
        }

        public AuctionHouseService.FilterMode
        filterMode() {
            return filterMode;
        }

        public String query() {
            return query;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public static final class OwnHolder
            implements InventoryHolder {

        private final Map<Integer, UUID>
                slotListings =
                new HashMap<>();
        private final int page;
        private Inventory inventory;

        private OwnHolder(
                int page
        ) {
            this.page = page;
        }

        public UUID listingAt(
                int slot
        ) {
            return slotListings.get(
                    slot
            );
        }

        public int page() {
            return page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public static final class HistoryHolder
            implements InventoryHolder {

        private final int page;
        private Inventory inventory;

        private HistoryHolder(
                int page
        ) {
            this.page = page;
        }

        public int page() {
            return page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public static final class ConfirmBuyHolder
            implements InventoryHolder {

        private final UUID listingId;
        private final int returnPage;
        private final AuctionHouseService.SortMode
                returnSort;
        private final AuctionHouseService.FilterMode
                returnFilter;
        private final String returnQuery;
        private Inventory inventory;

        private ConfirmBuyHolder(
                UUID listingId,
                int returnPage,
                AuctionHouseService.SortMode
                        returnSort,
                AuctionHouseService.FilterMode
                        returnFilter,
                String returnQuery
        ) {
            this.listingId = listingId;
            this.returnPage = returnPage;
            this.returnSort =
                    returnSort == null
                            ? AuctionHouseService
                            .SortMode
                            .LOWEST_UNIT_PRICE
                            : returnSort;
            this.returnFilter =
                    returnFilter == null
                            ? AuctionHouseService
                            .FilterMode
                            .ALL
                            : returnFilter;
            this.returnQuery =
                    returnQuery == null
                            ? ""
                            : returnQuery;
        }

        public UUID listingId() {
            return listingId;
        }

        public int returnPage() {
            return returnPage;
        }

        public AuctionHouseService.SortMode
        returnSort() {
            return returnSort;
        }

        public AuctionHouseService.FilterMode
        returnFilter() {
            return returnFilter;
        }

        public String returnQuery() {
            return returnQuery;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public static final class ConfirmCancelHolder
            implements InventoryHolder {

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
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
