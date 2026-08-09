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

public final class BalanceCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final EconomyService economyService;

    public BalanceCommand(
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
        if (!sender.hasPermission("mineacleeconomy.use")) {
            error(
                    sender,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (!economyService.enabled()) {
            error(sender, "&cEconomy is currently disabled");
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(
                        core.getMessage("general.players-only")
                );
                return true;
            }

            String balance = economyService.format(
                    economyService.getBalanceCents(
                            player.getUniqueId()
                    )
            );

            player.sendMessage(TextColor.color(
                    "&#bbbbbbBalance: &a" + balance
            ));
            SoundService.economyBalance(player, core);
            return true;
        }

        if (args.length != 1) {
            error(sender, "&cUsage: /balance [player]");
            return true;
        }

        OfflinePlayer target = DisplayNames.resolveOffline(args[0]);

        if (!knownPlayer(target)) {
            error(
                    sender,
                    core.getMessage("economy.player-not-found")
            );
            return true;
        }

        String balance = economyService.format(
                economyService.getBalanceCents(
                        target.getUniqueId()
                )
        );

        sender.sendMessage(TextColor.color(
                "&#bbbbbb"
                        + DisplayNames.displayName(target)
                        + "&#bbbbbb's balance: &a"
                        + balance
        ));

        if (sender instanceof Player player) {
            SoundService.economyBalance(player, core);
        }

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
                || !player.hasPermission("mineacleeconomy.use")
                || args.length != 1) {
            return List.of();
        }

        return PlayerTabComplete.onlinePlayers(
                player,
                args[0],
                true
        );
    }

    private boolean knownPlayer(OfflinePlayer player) {
        return player != null
                && (player.getName() != null
                || player.hasPlayedBefore()
                || economyService.hasAccount(
                player.getUniqueId()
        ));
    }

    private void error(
            CommandSender sender,
            String message
    ) {
        sender.sendMessage(TextColor.color(message));

        if (sender instanceof Player player) {
            SoundService.guiError(player, core);
        }
    }
}
