package net.mineacle.core.bounty.command;

import net.mineacle.core.Core;
import net.mineacle.core.bounty.BountyConfirmGui;
import net.mineacle.core.bounty.BountyMainGui;
import net.mineacle.core.bounty.BountyService;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BountyCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final BountyService bountyService;

    public BountyCommand(
            Core core,
            BountyService bountyService
    ) {
        this.core = core;
        this.bountyService = bountyService;
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

        if (!player.hasPermission("mineaclebounty.use")) {
            sendError(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (!bountyService.enabled()) {
            sendError(
                    player,
                    TextColor.color(
                            "&cBounty system is currently disabled"
                    )
            );
            return true;
        }

        if (args.length == 0
                || (args.length == 1
                && args[0].equalsIgnoreCase("list"))) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> BountyMainGui.open(
                            core,
                            player,
                            bountyService,
                            0
                    )
            );
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);

        if (isPlaceSubcommand(subcommand)) {
            if (args.length != 3) {
                sendError(
                        player,
                        TextColor.color(
                                "&cUsage: /bounty set <player> <amount>"
                        )
                );
                return true;
            }

            return confirm(
                    player,
                    args[1],
                    args[2]
            );
        }

        if (subcommand.equals("remove")) {
            return remove(player, args);
        }

        if (subcommand.equals("reload")) {
            return reload(player, args);
        }

        sendError(
                player,
                TextColor.color(
                        "&cUsage: /bounty set <player> <amount>"
                )
        );
        return true;
    }

    private boolean confirm(
            Player player,
            String targetInput,
            String amountInput
    ) {
        OfflinePlayer target = bountyService.resolveTarget(
                targetInput
        );

        if (target == null) {
            sendError(
                    player,
                    TextColor.color(
                            "&cThat player could not be found"
                    )
            );
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            sendError(
                    player,
                    TextColor.color(
                            "&cYou cannot place a bounty on yourself"
                    )
            );
            return true;
        }

        long amount = bountyService.parseAmount(amountInput);

        if (amount <= 0L) {
            sendError(
                    player,
                    TextColor.color(
                            "&cEnter a valid bounty amount"
                    )
            );
            return true;
        }

        long minimum = bountyService.minimumCents();

        if (amount < minimum) {
            sendError(
                    player,
                    TextColor.color(
                            "&cMinimum bounty is &a"
                                    + bountyService.format(minimum)
                    )
            );
            return true;
        }

        if (bountyService.wouldExceedMaximum(
                target.getUniqueId(),
                amount
        )) {
            sendError(
                    player,
                    TextColor.color(
                            "&cMaximum bounty is &a"
                                    + bountyService.format(
                                    bountyService.maximumCents()
                            )
                    )
            );
            return true;
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            sendError(
                    player,
                    TextColor.color(
                            "&cEconomy is not available"
                    )
            );
            return true;
        }

        if (!economy.has(player.getUniqueId(), amount)) {
            sendError(
                    player,
                    TextColor.color(
                            "&cYou do not have enough money"
                    )
            );
            return true;
        }

        BountyConfirmGui.open(
                core,
                player,
                target,
                amount,
                bountyService
        );
        return true;
    }

    private boolean remove(Player player, String[] args) {
        if (!player.hasPermission("mineaclebounty.admin")) {
            sendError(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length != 2) {
            sendError(
                    player,
                    TextColor.color(
                            "&cUsage: /bounty remove <player>"
                    )
            );
            return true;
        }

        OfflinePlayer target = bountyService.resolveTarget(args[1]);

        if (target == null) {
            sendError(
                    player,
                    TextColor.color(
                            "&cThat player could not be found"
                    )
            );
            return true;
        }

        BountyService.RemoveResult result =
                bountyService.removeDetailed(target.getUniqueId());

        switch (result.status()) {
            case SUCCESS -> {
                player.sendMessage(TextColor.color(
                        "&#bbbbbbRemoved &a"
                                + bountyService.format(
                                result.removedCents()
                        )
                                + " &#bbbbbbbounty from &#bbbbbb"
                                + bountyService.displayName(target)
                ));
                SoundService.guiConfirm(player, core);
            }
            case NOT_FOUND -> sendError(
                    player,
                    TextColor.color(
                            "&cThat player has no bounty"
                    )
            );
            case STORAGE_ERROR -> sendError(
                    player,
                    TextColor.color(
                            "&cCould not remove that bounty"
                    )
            );
        }

        return true;
    }

    private boolean reload(Player player, String[] args) {
        if (!player.hasPermission("mineaclebounty.admin")) {
            sendError(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length != 1) {
            sendError(
                    player,
                    TextColor.color(
                            "&cUsage: /bounty reload"
                    )
            );
            return true;
        }

        try {
            bountyService.reload();
            player.sendMessage(TextColor.color(
                    "&#bbbbbbBounty system reloaded"
            ));
            SoundService.guiConfirm(player, core);
        } catch (IOException exception) {
            core.getLogger().severe(
                    "Could not reload bounties.yml: "
                            + exception.getMessage()
            );
            sendError(
                    player,
                    TextColor.color(
                            "&cCould not reload the bounty system"
                    )
            );
        }

        return true;
    }

    private void sendError(Player player, String message) {
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission("mineaclebounty.use")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(
                    List.of("set", "list")
            );

            if (player.hasPermission("mineaclebounty.admin")) {
                options.add("remove");
                options.add("reload");
            }

            return PlayerTabComplete.options(
                    args[0],
                    options
            );
        }

        if (args.length == 2
                && isPlaceSubcommand(args[0])) {
            return PlayerTabComplete.onlinePlayers(
                    player,
                    args[1]
            );
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("remove")
                && player.hasPermission("mineaclebounty.admin")) {
            return PlayerTabComplete.options(
                    args[1],
                    bountyService.targetSuggestions()
            );
        }

        if (args.length == 3
                && isPlaceSubcommand(args[0])) {
            return PlayerTabComplete.options(
                    args[2],
                    List.of(
                            "1k",
                            "1.5k",
                            "10k",
                            "100k",
                            "1M",
                            "10M",
                            "1B"
                    )
            );
        }

        return List.of();
    }

    private boolean isPlaceSubcommand(String input) {
        return input.equalsIgnoreCase("set")
                || input.equalsIgnoreCase("add")
                || input.equalsIgnoreCase("place");
    }
}
