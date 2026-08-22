package net.mineacle.core.auctionhouse.command;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.gui.AuctionHouseGui;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.auctionhouse.service.AuctionOrderCrossing;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
        if (!(sender instanceof Player player)) {
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
                        : args[0].toLowerCase(
                                Locale.ROOT
                        );

        if (subcommand.equals("reload")) {
            reload(player);
            return true;
        }

        if (subcommand.equals("recovery")) {
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
            openBrowse(player, "");
            return true;
        }

        if (subcommand.equals("sell")
                || subcommand.equals("list")) {
            sell(player, args);
            return true;
        }

        if (subcommand.equals("items")
                || subcommand.equals("myitems")
                || subcommand.equals("listings")) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> AuctionHouseGui.openOwn(
                            player,
                            service,
                            0
                    )
            );
            return true;
        }

        String rawQuery =
                String.join(" ", args);

        if (service.searchQueryTooLong(rawQuery)) {
            fail(
                    player,
                    TextColor.color(
                            service.text(
                                    "messages.search-too-long",
                                    "&cSearch cannot exceed %max% characters",
                                    "%max%",
                                    String.valueOf(
                                            service.maxSearchLength()
                                    )
                            )
                    )
            );
            return true;
        }

        if (service.searchRateLimited(player)) {
            failPath(
                    player,
                    "messages.search-cooldown",
                    "&cPlease wait before searching again"
            );
            return true;
        }

        openBrowse(
                player,
                service.sanitizeSearchQuery(
                        rawQuery
                )
        );
        return true;
    }

    private void reload(Player player) {
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

        if (!service.healthy()) {
            fail(
                    player,
                    TextColor.color(
                            "&cAuction House reload completed but the system remains safety-blocked &#bbbbbb— use &#D0AFFF/ah recovery"
                    )
            );
            return;
        }

        player.sendMessage(
                TextColor.color(
                        service.text(
                                "messages.reload",
                                "&#bbbbbbAuction House reloaded"
                        )
                )
        );
        SoundService.guiConfirm(player, core);
    }

    private void openBrowse(
            Player player,
            String query
    ) {
        MenuHistory.openRoot(
                core,
                player,
                () -> AuctionHouseGui.openBrowse(
                        player,
                        service,
                        0,
                        service.defaultSort(),
                        AuctionHouseService.FilterMode.ALL,
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
        int listingAmount;

        if (args.length == 2) {
            ItemStack held =
                    service.previewHeldItem(player);
            listingAmount =
                    held == null
                            ? 0
                            : held.getAmount();
        } else {
            try {
                listingAmount =
                        Integer.parseInt(
                                args[2]
                        );
            } catch (NumberFormatException ignored) {
                failPath(
                        player,
                        "messages.invalid-amount",
                        "&cEnter a valid item amount"
                );
                return;
            }
        }

        AuctionOrderCrossing.Result result =
                AuctionOrderCrossing.create(
                        player,
                        service,
                        priceCents,
                        listingAmount,
                        null
                );

        handleCrossResult(
                player,
                result
        );
    }

    private void handleCrossResult(
            Player player,
            AuctionOrderCrossing.Result result
    ) {
        if (result.matchedAny()) {
            ItemStack item =
                    result.item();
            String itemName =
                    item == null
                            ? "item"
                            : service.itemName(item);

            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbbFilled &#B078FF"
                                    + result.matchedAmount()
                                    + "x "
                                    + itemName
                                    + " &#bbbbbbinto player Orders for &#11fc7b+"
                                    + service.format(
                                    result.matchedPayoutCents()
                            )
                    )
            );

            if (result.matchedPayoutCompleted()) {
                player.sendActionBar(
                        GuiText.component(
                                "&#11fc7b+"
                                        + service.format(
                                        result.matchedPayoutCents()
                                )
                        )
                );
            } else {
                player.sendMessage(
                        TextColor.color(
                                "&#bbbbbbOrder payout is finishing through recovery"
                        )
                );
            }
        }

        if (result.fullyMatched()) {
            if (result.matchedPayoutCompleted()) {
                SoundService.economyReceive(
                        player,
                        core
                );
            } else {
                SoundService.guiSelect(
                        player,
                        core
                );
            }
            return;
        }

        AuctionHouseService.CreateOutcome outcome =
                result.listingOutcome();

        if (outcome == null) {
            failPath(
                    player,
                    "messages.storage-error",
                    "&cCould not safely complete that Auction House action"
            );
            return;
        }

        handleCreateOutcome(
                player,
                outcome,
                result.remainingAmount()
        );
    }

    private void handleCreateOutcome(
            Player player,
            AuctionHouseService.CreateOutcome outcome,
            int listingAmount
    ) {
        switch (outcome.result()) {
            case SUCCESS -> {
                AuctionHouseListing listing =
                        outcome.listing();

                player.sendMessage(
                        TextColor.color(
                                service.text(
                                        "messages.listed",
                                        "&#bbbbbbListed &#B078FF%amount%x %item% &#bbbbbbfor &#11fc7b%price%",
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
                            "&cYour auction slots are full — cancel or reclaim a listing first"
                    );
            case INVALID_PRICE ->
                    failPath(
                            player,
                            "messages.invalid-price",
                            "&cEnter a valid auction price"
                    );
            case BELOW_MINIMUM ->
                    failBoth(
                            player,
                            TextColor.color(
                                    service.text(
                                            "messages.below-minimum",
                                            "&cPrice too low &#bbbbbb— minimum for this listing is &#11fc7b%price%",
                                            "%price%",
                                            service.format(
                                                    minimumPriceForHeldListing(
                                                            player,
                                                            listingAmount
                                                    )
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
                                            "&cMaximum auction price is &#11fc7b%price%",
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
            case APPRAISAL_FAILED ->
                    failPath(
                            player,
                            "messages.appraisal-failed",
                            "&cCould not verify the current server sell value — try again"
                    );
            case MARKET_FULL ->
                    failPath(
                            player,
                            "messages.market-full",
                            "&cThe Auction House is at its global safety limit — try again later"
                    );
            case STORAGE_ERROR ->
                    failPath(
                            player,
                            "messages.storage-error",
                            "&cCould not safely complete that Auction House action"
                    );
        }
    }

    private void recovery(Player player) {
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

        for (String summary : summaries) {
            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbb- &#D0AFFF"
                                    + summary
                    )
            );
        }
    }

    private long minimumPriceForHeldListing(
            Player player,
            int amount
    ) {
        ItemStack held =
                service.previewHeldItem(player);

        if (held == null
                || held.getType().isAir()
                || amount <= 0
                || amount > held.getAmount()) {
            return service.minPriceCents();
        }

        held.setAmount(amount);
        return service.minimumListingPriceCents(
                player,
                held
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

    private void failBoth(
            Player player,
            String message
    ) {
        player.sendMessage(message);
        player.sendActionBar(
                GuiText.component(message)
        );
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
        if (!(sender instanceof Player player)
                || !player.hasPermission(
                "mineacleauctionhouse.use"
        )) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> suggestions =
                    new ArrayList<>();

            if (service.canList(player)) {
                suggestions.add("sell");
            }

            suggestions.add("items");

            if (player.hasPermission(
                    "mineacleauctionhouse.admin"
            )) {
                suggestions.add("reload");
                suggestions.add("recovery");
            }

            return PlayerTabComplete.optionsFiltered(
                    args[0],
                    suggestions
            );
        }

        if (args.length == 2
                && isSell(args[0])
                && service.canList(player)) {
            return PlayerTabComplete.optionsFiltered(
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
                && service.canList(player)) {
            return PlayerTabComplete.optionsFiltered(
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

    private boolean isSell(String input) {
        return input.equalsIgnoreCase("sell")
                || input.equalsIgnoreCase("list");
    }
}
