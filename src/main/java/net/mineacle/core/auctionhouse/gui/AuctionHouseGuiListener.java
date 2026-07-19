package net.mineacle.core.auctionhouse.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuctionHouseGuiListener implements Listener {

    private final Core core;
    private final AuctionHouseService service;
    private final Map<UUID, SearchPrompt> searchPrompts = new ConcurrentHashMap<>();

    public AuctionHouseGuiListener(Core core, AuctionHouseService service) {
        this.core = core;
        this.service = service;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getInventory().getHolder() instanceof AuctionHouseGui.BrowseHolder holder) {
            handleBrowse(event, player, holder);
            return;
        }

        if (event.getInventory().getHolder() instanceof AuctionHouseGui.OwnHolder holder) {
            handleOwn(event, player, holder);
            return;
        }

        if (event.getInventory().getHolder() instanceof AuctionHouseGui.ConfirmBuyHolder holder) {
            handleConfirmBuy(event, player, holder);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        SearchPrompt prompt = searchPrompts.remove(player.getUniqueId());

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);

        String query = PlainTextComponentSerializer.plainText()
                .serialize(event.message())
                .trim();

        core.getServer().getScheduler().runTask(core, () -> {
            if (query.equalsIgnoreCase("cancel") || query.equalsIgnoreCase("cancelled")) {
                player.sendMessage(TextColor.color("&#bbbbbbAuction search cancelled"));
                AuctionHouseGui.openBrowse(
                        player,
                        service,
                        prompt.page(),
                        prompt.sortMode(),
                        prompt.query()
                );
                return;
            }

            if (query.isBlank()) {
                player.sendMessage(TextColor.color("&cSearch cannot be empty"));
                AuctionHouseGui.openBrowse(
                        player,
                        service,
                        prompt.page(),
                        prompt.sortMode(),
                        prompt.query()
                );
                return;
            }

            AuctionHouseGui.openBrowse(player, service, 0, prompt.sortMode(), query);
        });
    }

    private void handleBrowse(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.BrowseHolder holder
    ) {
        event.setCancelled(true);

        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getRawSlot();

        if (holder.slotListings.containsKey(slot)) {
            AuctionHouseListing listing = service.listing(holder.slotListings.get(slot));

            if (listing == null) {
                AuctionHouseGui.openBrowse(
                        player,
                        service,
                        holder.page,
                        holder.sortMode,
                        holder.query
                );
                return;
            }

            AuctionHouseGui.openConfirmBuy(player, service, listing);
            return;
        }

        if (slot == AuctionHouseGui.previousSlot() && holder.page > 0) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    holder.page - 1,
                    holder.sortMode,
                    holder.query
            );
            return;
        }

        if (slot == AuctionHouseGui.filterSlot()) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    0,
                    holder.sortMode.next(),
                    holder.query
            );
            return;
        }

        if (slot == AuctionHouseGui.worthSlot()) {
            player.closeInventory();
            player.performCommand("worth");
            return;
        }

        if (slot == AuctionHouseGui.refreshSlot()) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    holder.page,
                    holder.sortMode,
                    holder.query
            );
            return;
        }

        if (slot == AuctionHouseGui.searchSlot()) {
            searchPrompts.put(
                    player.getUniqueId(),
                    new SearchPrompt(holder.page, holder.sortMode, holder.query)
            );
            player.closeInventory();
            player.sendMessage(TextColor.color("&#bbbbbbType an item name to search auctions"));
            player.sendMessage(TextColor.color("&#bbbbbbType &ccancel &#bbbbbbto return"));
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
                    holder.page + 1,
                    holder.sortMode,
                    holder.query
            );
        }
    }

    private void handleOwn(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.OwnHolder holder
    ) {
        event.setCancelled(true);

        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getRawSlot();

        if (holder.slotListings.containsKey(slot)) {
            UUID id = holder.slotListings.get(slot);

            if (service.cancelListing(player, id)) {
                player.sendMessage(TextColor.color("&#bbbbbbAuction listing cancelled"));
            } else {
                player.sendMessage(TextColor.color("&cCould not cancel that listing"));
            }

            AuctionHouseGui.openOwn(player, service);
            return;
        }

        if (slot == AuctionHouseGui.ownWorthSlot()) {
            player.closeInventory();
            player.performCommand("worth");
            return;
        }

        if (slot == AuctionHouseGui.ownRefreshSlot()) {
            AuctionHouseGui.openOwn(player, service);
            return;
        }

        if (slot == AuctionHouseGui.ownListItemSlot()) {
            player.closeInventory();
            player.sendMessage(TextColor.color(
                    "&#bbbbbbHold an item and use &d/ah sell <price>"
            ));
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
        }
    }

    private void handleConfirmBuy(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.ConfirmBuyHolder holder
    ) {
        event.setCancelled(true);

        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot == AuctionHouseGui.confirmCancelSlot()) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    0,
                    AuctionHouseService.SortMode.LOWEST_PRICE,
                    ""
            );
            return;
        }

        if (slot != AuctionHouseGui.confirmBuySlot()) {
            return;
        }

        AuctionHouseService.BuyResult result = service.buy(player, holder.listingId);

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(TextColor.color("&#bbbbbbAuction purchase complete"));
                AuctionHouseGui.openBrowse(
                        player,
                        service,
                        0,
                        AuctionHouseService.SortMode.LOWEST_PRICE,
                        ""
                );
            }
            case OWN_ITEM -> player.sendMessage(TextColor.color("&cYou cannot buy your own listing"));
            case NOT_ENOUGH_MONEY -> player.sendMessage(TextColor.color("&cYou do not have enough money"));
            case INVENTORY_FULL -> player.sendMessage(TextColor.color("&cYour inventory is full"));
            case ECONOMY_MISSING -> player.sendMessage(TextColor.color("&cEconomy is not available"));
            case NOT_FOUND -> {
                player.sendMessage(TextColor.color("&cThat listing is no longer available"));
                AuctionHouseGui.openBrowse(
                        player,
                        service,
                        0,
                        AuctionHouseService.SortMode.LOWEST_PRICE,
                        ""
                );
            }
        }
    }

    private record SearchPrompt(
            int page,
            AuctionHouseService.SortMode sortMode,
            String query
    ) {
    }
}
