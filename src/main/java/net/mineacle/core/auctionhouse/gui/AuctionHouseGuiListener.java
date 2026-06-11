package net.mineacle.core.auctionhouse.gui;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.UUID;

public final class AuctionHouseGuiListener implements Listener {

    private final Core core;
    private final AuctionHouseService service;

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

    private void handleBrowse(InventoryClickEvent event, Player player, AuctionHouseGui.BrowseHolder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getRawSlot();

        if (holder.slotListings.containsKey(slot)) {
            AuctionHouseListing listing = service.listing(holder.slotListings.get(slot));

            if (listing == null) {
                AuctionHouseGui.openBrowse(player, service, holder.page, holder.sortMode, holder.query);
                return;
            }

            AuctionHouseGui.openConfirmBuy(player, service, listing);
            return;
        }

        if (slot == 45) {
            AuctionHouseGui.openBrowse(player, service, 0, holder.sortMode.next(), holder.query);
            return;
        }

        if (slot == 46) {
            player.closeInventory();
            player.performCommand("worth");
            return;
        }

        if (slot == 47) {
            AuctionHouseGui.openBrowse(player, service, holder.page, holder.sortMode, holder.query);
            return;
        }

        if (slot == 48) {
            player.closeInventory();
            player.sendMessage(TextColor.color("&#bbbbbbSearch with &d/ah <item>"));
            player.sendMessage(TextColor.color("&#bbbbbbExample: &d/ah mending"));
            return;
        }

        if (slot == 49) {
            AuctionHouseGui.openOwn(player, service);
            return;
        }

        if (slot == 51 && holder.page > 0) {
            AuctionHouseGui.openBrowse(player, service, holder.page - 1, holder.sortMode, holder.query);
            return;
        }

        if (slot == 53) {
            AuctionHouseGui.openBrowse(player, service, holder.page + 1, holder.sortMode, holder.query);
        }
    }

    private void handleOwn(InventoryClickEvent event, Player player, AuctionHouseGui.OwnHolder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
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

        if (slot == 45 || slot == 53) {
            AuctionHouseGui.openBrowse(player, service, 0, AuctionHouseService.SortMode.LOWEST_PRICE, "");
            return;
        }

        if (slot == 46) {
            player.closeInventory();
            player.performCommand("worth");
            return;
        }

        if (slot == 47) {
            AuctionHouseGui.openOwn(player, service);
        }
    }

    private void handleConfirmBuy(InventoryClickEvent event, Player player, AuctionHouseGui.ConfirmBuyHolder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot == 11) {
            AuctionHouseGui.openBrowse(player, service, 0, AuctionHouseService.SortMode.LOWEST_PRICE, "");
            return;
        }

        if (slot != 15) {
            return;
        }

        AuctionHouseService.BuyResult result = service.buy(player, holder.listingId);

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(TextColor.color("&#bbbbbbAuction purchase complete"));
                AuctionHouseGui.openBrowse(player, service, 0, AuctionHouseService.SortMode.LOWEST_PRICE, "");
            }
            case OWN_ITEM -> player.sendMessage(TextColor.color("&cYou cannot buy your own listing"));
            case NOT_ENOUGH_MONEY -> player.sendMessage(TextColor.color("&cYou do not have enough money"));
            case INVENTORY_FULL -> player.sendMessage(TextColor.color("&cYour inventory is full"));
            case ECONOMY_MISSING -> player.sendMessage(TextColor.color("&cEconomy is not available"));
            case NOT_FOUND -> {
                player.sendMessage(TextColor.color("&cThat listing is no longer available"));
                AuctionHouseGui.openBrowse(player, service, 0, AuctionHouseService.SortMode.LOWEST_PRICE, "");
            }
        }
    }
}
