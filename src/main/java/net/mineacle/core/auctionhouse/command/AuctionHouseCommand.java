package net.mineacle.core.auctionhouse.command;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.gui.AuctionHouseGui;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuctionHouseCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final AuctionHouseService service;

    public AuctionHouseCommand(
            Core core,
            AuctionHouseService service
    ) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender
                instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage(
                            "general.players-only"
                    )
            );
            return true;
        }

        if (!player.hasPermission(
                "mineacleauctionhouse.use"
        )) {
            fail(
                    player,
                    core.getMessage(
                            "general.no-permission"
                    )
            );
            return true;
        }

        String subcommand =
                args.length == 0
                        ? ""
                        : args[0]
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (subcommand.equals(
                "reload"
        )) {
            reload(player);
            return true;
        }

        if (subcommand.equals(
                "recovery"
        )) {
            recovery(player);
            return true;
        }

        if (!service.enabled()) {
            failPath(
                    player,
                    "messages.disabled",
                    "&cAuction House is currently disabled"
            );
            return true;
        }

        if (args.length == 0) {
            openBrowse(
                    player,
                    ""
            );
            return true;
        }

        if (subcommand.equals("sell")
                || subcommand.equals(
                "list"
        )) {
            sell(player, args);
            return true;
        }

        if (subcommand.equals("items")
                || subcommand.equals(
                "myitems"
        )
                || subcommand.equals(
                "listings"
        )) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () ->
                            AuctionHouseGui
                                    .openOwn(
                                            player,
                                            service,
                                            0
                                    )
            );
            return true;
        }

        String query =
                service.sanitizeSearchQuery(
                        String.join(
                                " ",
                                args
                        )
                );

        openBrowse(
                player,
                query
        );
        return true;
    }

    private void reload(
            Player player
    ) {
        if (!player.hasPermission(
                "mineacleauctionhouse.admin"
        )) {
            fail(
                    player,
                    core.getMessage(
                            "general.no-permission"
                    )
            );
            return;
        }

        service.load();

        player.sendMessage(
                TextColor.color(
                        service.text(
                                "messages.reload",
                                "&#bbbbbbAuction House reloaded"
                        )
                )
        );
        SoundService.guiConfirm(
                player,
                core
        );
    }

    private void openBrowse(
            Player player,
            String query
    ) {
        MenuHistory.openRoot(
                core,
                player,
                () ->
                        AuctionHouseGui
                                .openBrowse(
                                        player,
                                        service,
                                        0,
                                        service
                                                .defaultSort(),
                                        AuctionHouseService
                                                .FilterMode
                                                .ALL,
                                        query
                                )
        );
    }

    private void sell(
            Player player,
            String[] args
    ) {
        if (args.length < 2
                || args.length > 3) {
            failPath(
                    player,
                    "messages.sell-usage",
                    "&cUsage: /ah sell <price> [amount]"
            );
            return;
        }

        long priceCents =
                service.parsePriceCents(
                        args[1]
                );

        AuctionHouseService.CreateOutcome
                outcome;

        if (args.length == 2) {
            outcome =
                    service.createListing(
                            player,
                            priceCents
                    );
        } else {
            int amount;

            try {
                amount =
                        Integer.parseInt(
                                args[2]
                        );
            } catch (
                    NumberFormatException ignored
            ) {
                failPath(
                        player,
                        "messages.invalid-amount",
                        "&cEnter a valid item amount"
                );
                return;
            }

            outcome =
                    service.createListing(
                            player,
                            priceCents,
                            amount,
                            null
                    );
        }

        handleCreateOutcome(
                player,
                outcome
        );
    }

    private void handleCreateOutcome(
            Player player,
            AuctionHouseService.CreateOutcome
                    outcome
    ) {
        switch (outcome.result()) {
            case SUCCESS -> {
                AuctionHouseListing listing =
                        outcome.listing();

                player.sendMessage(
                        TextColor.color(
                                service.text(
                                        "messages.listed",
                                        "&#bbbbbbListed &#B078FF%amount%x %item% &#bbbbbbfor &a%price%",
                                        "%amount%",
                                        String.valueOf(
                                                listing.amount()
                                        ),
                                        "%item%",
                                        service.itemName(
                                                listing.item()
                                        ),
                                        "%price%",
                                        service.format(
                                                listing.priceCents()
                                        )
                                )
                        )
                );

                SoundService.guiConfirm(
                        player,
                        core
                );
            }
            case DISABLED ->
                    failPath(
                            player,
                            "messages.disabled",
                            "&cAuction House is currently disabled"
                    );
            case NO_PERMISSION ->
                    failPath(
                            player,
                            "messages.cannot-list",
                            "&cYou cannot list auction items"
                    );
            case NO_ITEM ->
                    failPath(
                            player,
                            "messages.no-item",
                            "&cHold the item you want to list"
                    );
            case ITEM_CHANGED ->
                    failPath(
                            player,
                            "messages.item-changed",
                            "&cThe item in your hand changed"
                    );
            case INVALID_AMOUNT ->
                    failPath(
                            player,
                            "messages.invalid-amount",
                            "&cEnter a valid item amount"
                    );
            case NO_SLOT ->
                    failPath(
                            player,
                            "messages.no-slot",
                            "&cYou do not have an auction slot available"
                    );
            case INVALID_PRICE ->
                    failPath(
                            player,
                            "messages.invalid-price",
                            "&cEnter a valid auction price"
                    );
            case BELOW_MINIMUM ->
                    fail(
                            player,
                            TextColor.color(
                                    service.text(
                                            "messages.below-minimum",
                                            "&cMinimum auction price is &a%price%",
                                            "%price%",
                                            service.format(
                                                    service.minPriceCents()
                                            )
                                    )
                            )
                    );
            case ABOVE_MAXIMUM ->
                    fail(
                            player,
                            TextColor.color(
                                    service.text(
                                            "messages.above-maximum",
                                            "&cMaximum auction price is &a%price%",
                                            "%price%",
                                            service.format(
                                                    service.maxPriceCents()
                                            )
                                    )
                            )
                    );
            case BLOCKED_ITEM ->
                    failPath(
                            player,
                            "messages.blocked-item",
                            "&cThat item cannot be listed"
                    );
            case FILLED_CONTAINER ->
                    failPath(
                            player,
                            "messages.filled-container",
                            "&cEmpty that container before listing it"
                    );
            case OVERSIZED_ITEM ->
                    failPath(
                            player,
                            "messages.oversized-item",
                            "&cThat item contains too much data to list safely"
                    );
            case STORAGE_ERROR ->
                    failPath(
                            player,
                            "messages.storage-error",
                            "&cCould not safely save that listing"
                    );
        }
    }

    private void recovery(
            Player player
    ) {
        if (!player.hasPermission(
                "mineacleauctionhouse.admin"
        )) {
            fail(
                    player,
                    core.getMessage(
                            "general.no-permission"
                    )
            );
            return;
        }

        List<String> summaries =
                service.recoverySummaries();

        if (summaries.isEmpty()) {
            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbbNo Auction House recovery transactions"
                    )
            );
            return;
        }

        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbAuction recovery transactions: &#D0AFFF"
                                + summaries.size()
                )
        );

        for (String summary
                : summaries) {
            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbb- &#D0AFFF"
                                    + summary
                    )
            );
        }

        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbRecovery files: &#D0AFFF"
                                + service.recoveryPath()
                )
        );
    }

    private void failPath(
            Player player,
            String path,
            String fallback
    ) {
        fail(
                player,
                TextColor.color(
                        service.text(
                                path,
                                fallback
                        )
                )
        );
    }

    private void fail(
            Player player,
            String message
    ) {
        player.sendMessage(message);
        SoundService.guiError(
                player,
                core
        );
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender
                instanceof Player player)
                || !player.hasPermission(
                "mineacleauctionhouse.use"
        )) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> suggestions =
                    new ArrayList<>();

            if (service.canList(
                    player
            )) {
                suggestions.add("sell");
            }

            suggestions.add("items");

            if (player.hasPermission(
                    "mineacleauctionhouse.admin"
            )) {
                suggestions.add("reload");
                suggestions.add("recovery");
            }

            return PlayerTabComplete
                    .optionsFiltered(
                            args[0],
                            suggestions
                    );
        }

        if (args.length == 2
                && isSell(args[0])
                && service.canList(
                player
        )) {
            return PlayerTabComplete
                    .optionsFiltered(
                            args[1],
                            List.of(
                                    "100",
                                    "1k",
                                    "10k",
                                    "100k"
                            )
                    );
        }

        if (args.length == 3
                && isSell(args[0])
                && service.canList(
                player
        )) {
            return PlayerTabComplete
                    .optionsFiltered(
                            args[2],
                            List.of(
                                    "1",
                                    "16",
                                    "32",
                                    "64"
                            )
                    );
        }

        return List.of();
    }

    private boolean isSell(
            String input
    ) {
        return input.equalsIgnoreCase(
                "sell"
        )
                || input.equalsIgnoreCase(
                "list"
        );
    }
}
