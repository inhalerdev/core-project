package net.mineacle.core.auctionhouse.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuctionHouseGuiListener implements Listener {

    private final Core core;
    private final AuctionHouseService service;
    private final Map<UUID, SearchPrompt> searchPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> searchTimeoutTasks = new ConcurrentHashMap<>();

    public AuctionHouseGuiListener(
            Core core,
            AuctionHouseService service
    ) {
        this.core = core;
        this.service = service;
    }

    public void shutdown() {
        for (BukkitTask task : searchTimeoutTasks.values()) {
            task.cancel();
        }

        searchTimeoutTasks.clear();
        searchPrompts.clear();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Object holder = event.getView().getTopInventory().getHolder();

        if (!(holder instanceof AuctionHouseGui.BrowseHolder)
                && !(holder instanceof AuctionHouseGui.OwnHolder)
                && !(holder instanceof AuctionHouseGui.ConfirmBuyHolder)
                && !(holder instanceof AuctionHouseGui.ConfirmCancelHolder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() == null
                || event.getClickedInventory()
                != event.getView().getTopInventory()) {
            return;
        }

        if (!service.enabled()) {
            player.closeInventory();
            fail(
                    player,
                    TextColor.color("&cAuction House is currently disabled")
            );
            return;
        }

        if (holder instanceof AuctionHouseGui.BrowseHolder browseHolder) {
            handleBrowse(event, player, browseHolder);
            return;
        }

        if (holder instanceof AuctionHouseGui.OwnHolder ownHolder) {
            handleOwn(event, player, ownHolder);
            return;
        }

        if (holder instanceof AuctionHouseGui.ConfirmBuyHolder buyHolder) {
            handleConfirmBuy(event, player, buyHolder);
            return;
        }

        if (holder instanceof AuctionHouseGui.ConfirmCancelHolder cancelHolder) {
            handleConfirmCancel(event, player, cancelHolder);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();

        if (!(holder instanceof AuctionHouseGui.BrowseHolder)
                && !(holder instanceof AuctionHouseGui.OwnHolder)
                && !(holder instanceof AuctionHouseGui.ConfirmBuyHolder)
                && !(holder instanceof AuctionHouseGui.ConfirmCancelHolder)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        SearchPrompt prompt = takeSearchPrompt(player.getUniqueId());

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);

        String rawQuery = PlainTextComponentSerializer
                .plainText()
                .serialize(event.message())
                .trim();

        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (rawQuery.equalsIgnoreCase("cancel")
                    || rawQuery.equalsIgnoreCase("cancelled")) {
                player.sendMessage(
                        TextColor.color(
                                "&#bbbbbbAuction search cancelled"
                        )
                );
                SoundService.guiCancel(player, core);
                openBrowse(player, prompt);
                return;
            }

            if (rawQuery.equalsIgnoreCase("clear")) {
                player.sendMessage(
                        TextColor.color(
                                "&#bbbbbbAuction search cleared"
                        )
                );
                AuctionHouseGui.openBrowse(
                        player,
                        service,
                        0,
                        prompt.sortMode(),
                        ""
                );
                return;
            }

            if (rawQuery.isBlank()) {
                fail(
                        player,
                        TextColor.color("&cSearch cannot be empty")
                );
                openBrowse(player, prompt);
                return;
            }

            if (service.searchQueryTooLong(rawQuery)) {
                fail(
                        player,
                        TextColor.color(
                                "&cSearch cannot exceed "
                                        + service.maxSearchLength()
                                        + " characters"
                        )
                );
                openBrowse(player, prompt);
                return;
            }

            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    0,
                    prompt.sortMode(),
                    service.sanitizeSearchQuery(rawQuery)
            );
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSearchPrompt(event.getPlayer().getUniqueId());
    }

    private void handleBrowse(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.BrowseHolder holder
    ) {
        int slot = event.getRawSlot();
        UUID listingId = holder.listingAt(slot);

        if (listingId != null) {
            AuctionHouseListing listing = service.listing(listingId);

            if (listing == null) {
                fail(
                        player,
                        TextColor.color(
                                "&cThat listing is no longer available"
                        )
                );
                reopenBrowse(player, holder);
                return;
            }

            AuctionHouseGui.openConfirmBuy(
                    player,
                    service,
                    listing,
                    holder.page(),
                    holder.sortMode(),
                    holder.query()
            );
            return;
        }

        if (slot == AuctionHouseGui.previousSlot()
                && holder.page() > 0) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    holder.page() - 1,
                    holder.sortMode(),
                    holder.query()
            );
            return;
        }

        if (slot == AuctionHouseGui.filterSlot()) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    0,
                    holder.sortMode().next(),
                    holder.query()
            );
            return;
        }

        if (slot == AuctionHouseGui.worthSlot()) {
            player.closeInventory();
            player.performCommand("worth");
            return;
        }

        if (slot == AuctionHouseGui.refreshSlot()) {
            reopenBrowse(player, holder);
            return;
        }

        if (slot == AuctionHouseGui.searchSlot()) {
            beginSearch(player, holder);
            return;
        }

        if (slot == AuctionHouseGui.ownItemsSlot()) {
            AuctionHouseGui.openOwn(player, service);
            return;
        }

        if (slot == AuctionHouseGui.nextSlot()) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    holder.page() + 1,
                    holder.sortMode(),
                    holder.query()
            );
        }
    }

    private void handleOwn(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.OwnHolder holder
    ) {
        int slot = event.getRawSlot();
        UUID listingId = holder.listingAt(slot);

        if (listingId != null) {
            AuctionHouseListing listing = service.listing(listingId);

            if (listing == null) {
                fail(
                        player,
                        TextColor.color(
                                "&cThat listing is no longer available"
                        )
                );
                AuctionHouseGui.openOwn(
                        player,
                        service,
                        holder.page()
                );
                return;
            }

            AuctionHouseGui.openConfirmCancel(
                    player,
                    service,
                    listing,
                    holder.page()
            );
            return;
        }

        if (slot == AuctionHouseGui.ownPreviousSlot()
                && holder.page() > 0) {
            AuctionHouseGui.openOwn(
                    player,
                    service,
                    holder.page() - 1
            );
            return;
        }

        if (slot == AuctionHouseGui.ownBackSlot()) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    0,
                    AuctionHouseService.SortMode.LOWEST_PRICE,
                    ""
            );
            return;
        }

        if (slot == AuctionHouseGui.ownWorthSlot()) {
            player.closeInventory();
            player.performCommand("worth");
            return;
        }

        if (slot == AuctionHouseGui.ownRefreshSlot()) {
            AuctionHouseGui.openOwn(
                    player,
                    service,
                    holder.page()
            );
            return;
        }

        if (slot == AuctionHouseGui.ownListItemSlot()) {
            player.closeInventory();
            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbbHold an item and use "
                                    + "&d/ah sell <price>"
                    )
            );
            return;
        }

        if (slot == AuctionHouseGui.ownNextSlot()) {
            AuctionHouseGui.openOwn(
                    player,
                    service,
                    holder.page() + 1
            );
        }
    }

    private void handleConfirmBuy(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.ConfirmBuyHolder holder
    ) {
        int slot = event.getRawSlot();

        if (slot == AuctionHouseGui.confirmCancelSlot()) {
            SoundService.guiCancel(player, core);
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    holder.returnPage(),
                    holder.returnSort(),
                    holder.returnQuery()
            );
            return;
        }

        if (slot != AuctionHouseGui.confirmActionSlot()) {
            return;
        }

        AuctionHouseService.BuyOutcome outcome = service.buy(
                player,
                holder.listingId()
        );

        AuctionHouseListing listing = outcome.listing();

        switch (outcome.result()) {
            case SUCCESS -> {
                player.sendMessage(
                        TextColor.color(
                                "&#bbbbbbPurchased &d"
                                        + service.itemName(listing.item())
                                        + " &#bbbbbbfrom &#bbbbbb"
                                        + service.sellerDisplayName(listing)
                                        + " &#bbbbbbfor &a"
                                        + service.format(listing.priceCents())
                        )
                );
                SoundService.guiConfirm(player, core);

                AuctionHouseGui.openBrowse(
                        player,
                        service,
                        holder.returnPage(),
                        holder.returnSort(),
                        holder.returnQuery()
                );
            }
            case OWN_ITEM -> fail(
                    player,
                    TextColor.color(
                            "&cYou cannot buy your own listing"
                    )
            );
            case NOT_ENOUGH_MONEY -> fail(
                    player,
                    TextColor.color(
                            "&cYou do not have enough money"
                    )
            );
            case INVENTORY_FULL -> fail(
                    player,
                    TextColor.color("&cYour inventory is full")
            );
            case ECONOMY_MISSING -> fail(
                    player,
                    TextColor.color("&cEconomy is not available")
            );
            case PAYMENT_FAILED -> fail(
                    player,
                    TextColor.color(
                            "&cCould not complete that payment"
                    )
            );
            case STORAGE_ERROR -> fail(
                    player,
                    TextColor.color(
                            "&cCould not safely complete that purchase"
                    )
            );
            case NOT_FOUND -> {
                fail(
                        player,
                        TextColor.color(
                                "&cThat listing is no longer available"
                        )
                );
                AuctionHouseGui.openBrowse(
                        player,
                        service,
                        holder.returnPage(),
                        holder.returnSort(),
                        holder.returnQuery()
                );
            }
        }
    }

    private void handleConfirmCancel(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.ConfirmCancelHolder holder
    ) {
        int slot = event.getRawSlot();

        if (slot == AuctionHouseGui.confirmCancelSlot()) {
            SoundService.guiCancel(player, core);
            AuctionHouseGui.openOwn(
                    player,
                    service,
                    holder.returnPage()
            );
            return;
        }

        if (slot != AuctionHouseGui.confirmActionSlot()) {
            return;
        }

        AuctionHouseListing listing = service.listing(
                holder.listingId()
        );
        AuctionHouseService.CancelResult result = service.cancelListing(
                player,
                holder.listingId()
        );

        switch (result) {
            case SUCCESS -> {
                String itemName = listing == null
                        ? "item"
                        : service.itemName(listing.item());

                player.sendMessage(
                        TextColor.color(
                                "&#bbbbbbCancelled listing for &d"
                                        + itemName
                        )
                );
                SoundService.guiConfirm(player, core);
                AuctionHouseGui.openOwn(
                        player,
                        service,
                        holder.returnPage()
                );
            }
            case NOT_FOUND -> {
                fail(
                        player,
                        TextColor.color(
                                "&cThat listing is no longer available"
                        )
                );
                AuctionHouseGui.openOwn(
                        player,
                        service,
                        holder.returnPage()
                );
            }
            case NOT_OWNER -> fail(
                    player,
                    core.getMessage("general.no-permission")
            );
            case INVENTORY_FULL -> fail(
                    player,
                    TextColor.color(
                            "&cYour inventory does not have enough space"
                    )
            );
            case STORAGE_ERROR -> fail(
                    player,
                    TextColor.color(
                            "&cCould not safely cancel that listing"
                    )
            );
        }
    }

    private void beginSearch(
            Player player,
            AuctionHouseGui.BrowseHolder holder
    ) {
        UUID playerId = player.getUniqueId();
        clearSearchPrompt(playerId);

        SearchPrompt prompt = new SearchPrompt(
                holder.page(),
                holder.sortMode(),
                holder.query()
        );

        searchPrompts.put(playerId, prompt);

        BukkitTask timeoutTask = core
                .getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> expireSearchPrompt(playerId, prompt),
                        service.searchPromptTimeoutTicks()
                );

        searchTimeoutTasks.put(playerId, timeoutTask);

        player.closeInventory();
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType an item name to search auctions"
                )
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType &ccancel &#bbbbbbto return "
                                + "or &cclear &#bbbbbbto clear the search"
                )
        );
    }

    private void expireSearchPrompt(
            UUID playerId,
            SearchPrompt prompt
    ) {
        if (!searchPrompts.remove(playerId, prompt)) {
            return;
        }

        searchTimeoutTasks.remove(playerId);

        Player player = Bukkit.getPlayer(playerId);

        if (player != null && player.isOnline()) {
            player.sendMessage(
                    TextColor.color("&cAuction search timed out")
            );
            SoundService.guiError(player, core);
        }
    }

    private SearchPrompt takeSearchPrompt(UUID playerId) {
        SearchPrompt prompt = searchPrompts.remove(playerId);
        BukkitTask task = searchTimeoutTasks.remove(playerId);

        if (task != null) {
            task.cancel();
        }

        return prompt;
    }

    private void clearSearchPrompt(UUID playerId) {
        searchPrompts.remove(playerId);

        BukkitTask task = searchTimeoutTasks.remove(playerId);

        if (task != null) {
            task.cancel();
        }
    }

    private void reopenBrowse(
            Player player,
            AuctionHouseGui.BrowseHolder holder
    ) {
        AuctionHouseGui.openBrowse(
                player,
                service,
                holder.page(),
                holder.sortMode(),
                holder.query()
        );
    }

    private void openBrowse(
            Player player,
            SearchPrompt prompt
    ) {
        AuctionHouseGui.openBrowse(
                player,
                service,
                prompt.page(),
                prompt.sortMode(),
                prompt.query()
        );
    }

    private void fail(Player player, String message) {
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }

    private record SearchPrompt(
            int page,
            AuctionHouseService.SortMode sortMode,
            String query
    ) {
    }
}
