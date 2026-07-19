package net.mineacle.core.economy.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class PayCommand
        implements CommandExecutor, TabCompleter {

    private static final List<String> AMOUNT_SUGGESTIONS = List.of(
            "0.01",
            "1",
            "100",
            "1k",
            "10k",
            "100k",
            "1M"
    );

    private final Core core;
    private final EconomyService economyService;

    public PayCommand(
            Core core,
            EconomyService economyService
    ) {
        this.core = core;
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (!player.hasPermission("mineacleeconomy.pay")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length != 2) {
            error(
                    player,
                    core.getMessage("economy.pay-usage")
            );
            return true;
        }

        OfflinePlayer target = DisplayNames.resolveOffline(args[0]);

        if (!knownPlayer(target)) {
            error(
                    player,
                    core.getMessage("economy.player-not-found")
            );
            return true;
        }

        long cents = economyService.parseAmountToCents(args[1]);

        if (cents <= 0L) {
            error(
                    player,
                    core.getMessage("economy.invalid-amount")
            );
            return true;
        }

        economyService.pay(player, target, cents);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission("mineacleeconomy.pay")) {
            return List.of();
        }

        if (args.length == 1) {
            return PlayerTabComplete.onlinePlayers(
                    player,
                    args[0]
            );
        }

        if (args.length == 2) {
            return PlayerTabComplete.options(
                    args[1],
                    AMOUNT_SUGGESTIONS
            );
        }

        return List.of();
    }

    private boolean knownPlayer(OfflinePlayer player) {
        return player != null
                && (player.getName() != null
                || player.hasPlayedBefore()
                || economyService.hasAccount(
                player.getUniqueId()
        ));
    }

    private void error(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }
}
