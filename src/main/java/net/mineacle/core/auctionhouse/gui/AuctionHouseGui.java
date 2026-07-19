package net.mineacle.core.auctionhouse.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AuctionHouseGui {

    public static final int SIZE = 54;

    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_FILTER = 47;
    private static final int SLOT_WORTH = 48;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_SEARCH = 50;
    private static final int SLOT_OWN_ITEMS = 51;
    private static final int SLOT_NEXT = 53;

    private static final int SLOT_OWN_WORTH = 46;
    private static final int SLOT_OWN_REFRESH = 49;
    private static final int SLOT_OWN_LIST_ITEM = 51;
    private static final int SLOT_OWN_BACK = 53;

    private static final int SLOT_CONFIRM_CANCEL = 11;
    private static final int SLOT_CONFIRM_ITEM = 13;
    private static final int SLOT_CONFIRM_BUY = 15;

    private AuctionHouseGui() {
    }

    public static void openBrowse(
            org.bukkit.entity.Player player,
            AuctionHouseService service,
            int page,
            AuctionHouseService.SortMode sortMode,
            String query
    ) {
        List<AuctionHouseListing> listings = service.search(query, sortMode);
        int maxPage = Math.max(0, (listings.size() - 1) / service.pageSize());
        page = Math.max(0, Math.min(page, maxPage));

        BrowseHolder holder = new BrowseHolder(page, sortMode, query);
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                legacy("Auction (Page " + (page + 1) + ")")
        );
        holder.inventory = inventory;

        int start = page * service.pageSize();

        for (int slot = 0; slot < service.pageSize(); slot++) {
            int index = start + slot;

            if (index >= listings.size()) {
                break;
            }

            AuctionHouseListing listing = listings.get(index);
            inventory.setItem(slot, listingItem(service, listing));
            holder.slotListings.put(slot, listing.id());
        }

        if (page > 0) {
            inventory.setItem(
                    SLOT_PREVIOUS,
                    item(
                            Material.ARROW,
                            "&dPrevious Page",
                            "&#bbbbbbClick to view the previous page"
                    )
            );
        }

        inventory.setItem(
                SLOT_FILTER,
                item(
                        Material.HOPPER,
                        "&dFilter",
                        "&#bbbbbbCurrent: &#ff88ff" + sortMode.label(),
                        "",
                        "&#bbbbbbClick to change the filter"
                )
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
                        Material.EMERALD,
                        "&dRefresh",
                        "&#bbbbbbClick to refresh auctions"
                )
        );

        if (query == null || query.isBlank()) {
            inventory.setItem(
                    SLOT_SEARCH,
                    item(
                            Material.OAK_SIGN,
                            "&dSearch",
                            "&#bbbbbbClick to search auctions"
                    )
            );
        } else {
            inventory.setItem(
                    SLOT_SEARCH,
                    item(
                            Material.OAK_SIGN,
                            "&dSearch",
                            "&#bbbbbbCurrent: &#ff88ff" + query,
                            "",
                            "&#bbbbbbClick to search again"
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

        if ((page + 1) * service.pageSize() < listings.size()) {
            inventory.setItem(
                    SLOT_NEXT,
                    item(
                            Material.ARROW,
                            "&dNext Page",
                            "&#bbbbbbClick to view the next page"
                    )
            );
        }

        player.openInventory(inventory);
    }

    public static void openOwn(org.bukkit.entity.Player player, AuctionHouseService service) {
        List<AuctionHouseListing> listings = service.ownerListings(player.getUniqueId());
        int limit = service.listingLimit(player);

        OwnHolder holder = new OwnHolder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, legacy("Auction -> Your Items"));
        holder.inventory = inventory;

        for (int slot = 0; slot < Math.min(45, listings.size()); slot++) {
            AuctionHouseListing listing = listings.get(slot);
            inventory.setItem(slot, ownListingItem(service, listing));
            holder.slotListings.put(slot, listing.id());
        }

        for (int slot = listings.size(); slot < Math.min(45, limit); slot++) {
            inventory.setItem(
                    slot,
                    item(
                            Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                            "&dAvailable Slot",
                            "&#bbbbbbUse &d/ah sell <price>",
                            "&#bbbbbbwhile holding an item"
                    )
            );
        }

        for (int slot = Math.max(0, limit); slot < 45; slot++) {
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
                        Material.EMERALD,
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
                SLOT_OWN_BACK,
                item(
                        Material.ARROW,
                        "&dBack",
                        "&#bbbbbbClick to return"
                )
        );

        player.openInventory(inventory);
    }

    public static void openConfirmBuy(
            org.bukkit.entity.Player player,
            AuctionHouseService service,
            AuctionHouseListing listing
    ) {
        ConfirmBuyHolder holder = new ConfirmBuyHolder(listing.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, legacy("Confirm Purchase"));
        holder.inventory = inventory;

        inventory.setItem(
                SLOT_CONFIRM_CANCEL,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        "&#bbbbbbClick to cancel"
                )
        );

        inventory.setItem(SLOT_CONFIRM_ITEM, listingItem(service, listing));

        inventory.setItem(
                SLOT_CONFIRM_BUY,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aConfirm",
                        "&#bbbbbbBuy this item for &a" + service.format(listing.priceCents())
                )
        );

        player.openInventory(inventory);
    }

    private static ItemStack listingItem(
            AuctionHouseService service,
            AuctionHouseListing listing
    ) {
        ItemStack item = listing.item().clone();
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

        lore.add(legacy("&#bbbbbbPrice: &a" + service.format(listing.priceCents())));
        lore.add(legacy("&#bbbbbbSeller: &#ff88ff" + listing.ownerName()));
        lore.add(legacy(""));
        lore.add(legacy("&dClick to buy"));

        meta.lore(noItalic(lore));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack ownListingItem(
            AuctionHouseService service,
            AuctionHouseListing listing
    ) {
        ItemStack item = listingItem(service, listing);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore())
                : new ArrayList<>();

        lore.add(legacy("&cClick to cancel listing"));
        meta.lore(noItalic(lore));
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack item(Material material, String name, String... loreLines) {
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

    public static int filterSlot() {
        return SLOT_FILTER;
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

    public static int ownWorthSlot() {
        return SLOT_OWN_WORTH;
    }

    public static int ownRefreshSlot() {
        return SLOT_OWN_REFRESH;
    }

    public static int ownListItemSlot() {
        return SLOT_OWN_LIST_ITEM;
    }

    public static int ownBackSlot() {
        return SLOT_OWN_BACK;
    }

    public static int confirmCancelSlot() {
        return SLOT_CONFIRM_CANCEL;
    }

    public static int confirmBuySlot() {
        return SLOT_CONFIRM_BUY;
    }

    private static List<Component> noItalic(List<Component> input) {
        List<Component> output = new ArrayList<>();

        for (Component component : input) {
            output.add(component.decoration(TextDecoration.ITALIC, false));
        }

        return output;
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(text == null ? "" : text))
                .decoration(TextDecoration.ITALIC, false);
    }

    public static final class BrowseHolder implements InventoryHolder {
        public final java.util.Map<Integer, UUID> slotListings = new java.util.HashMap<>();
        public final int page;
        public final AuctionHouseService.SortMode sortMode;
        public final String query;
        private Inventory inventory;

        public BrowseHolder(int page, AuctionHouseService.SortMode sortMode, String query) {
            this.page = page;
            this.sortMode = sortMode;
            this.query = query == null ? "" : query;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class OwnHolder implements InventoryHolder {
        public final java.util.Map<Integer, UUID> slotListings = new java.util.HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class ConfirmBuyHolder implements InventoryHolder {
        public final UUID listingId;
        private Inventory inventory;

        public ConfirmBuyHolder(UUID listingId) {
            this.listingId = listingId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
