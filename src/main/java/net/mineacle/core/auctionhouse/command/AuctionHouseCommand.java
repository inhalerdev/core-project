package net.mineacle.core.auctionhouse.command;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.gui.AuctionHouseGui;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuctionHouseCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final AuctionHouseService service;

    public AuctionHouseCommand(Core core, AuctionHouseService service) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineacleauctionhouse.use")) {
            fail(player, core.getMessage("general.no-permission"));
            return true;
        }

        String subcommand = args.length == 0
                ? ""
                : args[0].toLowerCase(Locale.ROOT);

        if (subcommand.equals("reload")) {
            if (!player.hasPermission("mineacleauctionhouse.admin")) {
                fail(player, core.getMessage("general.no-permission"));
                return true;
            }

            service.load();
            player.sendMessage(
                    TextColor.color("&#bbbbbbAuction House reloaded")
            );
            SoundService.guiConfirm(player, core);
            return true;
        }

        if (!service.enabled()) {
            fail(
                    player,
                    TextColor.color("&cAuction House is currently disabled")
            );
            return true;
        }

        if (args.length == 0) {
            AuctionHouseGui.openBrowse(
                    player,
                    service,
                    0,
                    AuctionHouseService.SortMode.LOWEST_PRICE,
                    ""
            );
            return true;
        }

        if (subcommand.equals("sell") || subcommand.equals("list")) {
            sell(player, args);
            return true;
        }

        if (subcommand.equals("items")
                || subcommand.equals("myitems")
                || subcommand.equals("listings")) {
            AuctionHouseGui.openOwn(player, service);
            return true;
        }

        String query = service.sanitizeSearchQuery(String.join(" ", args));

        AuctionHouseGui.openBrowse(
                player,
                service,
                0,
                AuctionHouseService.SortMode.LOWEST_PRICE,
                query
        );
        return true;
    }

    private void sell(Player player, String[] args) {
        if (args.length != 2) {
            fail(
                    player,
                    TextColor.color("&cUsage: /ah sell <price>")
            );
            return;
        }

        long priceCents = service.parsePriceCents(args[1]);
        AuctionHouseService.CreateOutcome outcome = service.createListing(
                player,
                priceCents
        );

        switch (outcome.result()) {
            case SUCCESS -> {
                AuctionHouseListing listing = outcome.listing();

                player.sendMessage(
                        TextColor.color(
                                "&#bbbbbbListed &d"
                                        + service.itemName(listing.item())
                                        + " &#bbbbbbfor &a"
                                        + service.format(listing.priceCents())
                        )
                );
                SoundService.guiConfirm(player, core);
            }
            case DISABLED -> fail(
                    player,
                    TextColor.color("&cAuction House is currently disabled")
            );
            case NO_PERMISSION -> fail(
                    player,
                    TextColor.color("&cYou cannot list auction items")
            );
            case NO_ITEM -> fail(
                    player,
                    TextColor.color("&cHold the item you want to sell")
            );
            case NO_SLOT -> fail(
                    player,
                    TextColor.color(
                            "&cYou do not have any auction slots available"
                    )
            );
            case INVALID_PRICE -> fail(
                    player,
                    TextColor.color("&cEnter a valid auction price")
            );
            case BELOW_MINIMUM -> fail(
                    player,
                    TextColor.color(
                            "&cMinimum auction price is &a"
                                    + service.format(service.minPriceCents())
                    )
            );
            case ABOVE_MAXIMUM -> fail(
                    player,
                    TextColor.color(
                            "&cMaximum auction price is &a"
                                    + service.format(service.maxPriceCents())
                    )
            );
            case STORAGE_ERROR -> fail(
                    player,
                    TextColor.color(
                            "&cCould not save that auction listing"
                    )
            );
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission("mineacleauctionhouse.use")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();

            if (service.canList(player)) {
                suggestions.add("sell");
            }

            suggestions.add("items");

            if (player.hasPermission("mineacleauctionhouse.admin")) {
                suggestions.add("reload");
            }

            return PlayerTabComplete.options(args[0], suggestions);
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("sell")
                || args[0].equalsIgnoreCase("list"))
                && service.canList(player)) {
            return PlayerTabComplete.options(
                    args[1],
                    List.of("100", "1k", "10k", "100k")
            );
        }

        return List.of();
    }

    private void fail(Player player, String message) {
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }
}
