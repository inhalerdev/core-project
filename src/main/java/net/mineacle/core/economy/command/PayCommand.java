package net.mineacle.core.economy.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class PayCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final EconomyService economyService;

    public PayCommand(Core core, EconomyService economyService) {
        this.core = core;
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineacleeconomy.pay")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(core.getMessage("economy.pay-usage"));
            SoundService.guiError(player, core);
            return true;
        }

        OfflinePlayer target = DisplayNames.resolveOffline(args[0]);

        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            target = Bukkit.getOfflinePlayer(args[0]);
        }

        if (target.getName() == null && !target.hasPlayedBefore()) {
            player.sendMessage(core.getMessage("economy.player-not-found"));
            SoundService.guiError(player, core);
            return true;
        }

        long cents = economyService.parseAmountToCents(args[1]);

        if (cents < 1L) {
            player.sendMessage(core.getMessage("economy.invalid-amount"));
            SoundService.guiError(player, core);
            return true;
        }

        economyService.pay(player, target, cents);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            return PlayerTabComplete.onlinePlayers(player, args[0]);
        }

        if (args.length == 2) {
            String partial = args[1].toLowerCase();
            List<String> amounts = List.of("10", "100", "1000", "10k", "100k", "1m");
            List<String> completions = new ArrayList<>();

            for (String amount : amounts) {
                if (amount.startsWith(partial)) {
                    completions.add(amount);
                }
            }

            return completions;
        }

        return new ArrayList<>();
    }
}
