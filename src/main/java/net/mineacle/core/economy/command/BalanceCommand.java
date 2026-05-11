package net.mineacle.core.economy.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
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
import java.util.Locale;

public final class BalanceCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final EconomyService economyService;

    public BalanceCommand(Core core, EconomyService economyService) {
        this.core = core;
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mineacleeconomy.use")) {
            sender.sendMessage(core.getMessage("general.no-permission"));

            if (sender instanceof Player player) {
                SoundService.guiError(player, core);
            }

            return true;
        }

        if (args.length < 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(core.getMessage("general.players-only"));
                return true;
            }

            String balance = economyService.format(economyService.getBalanceCents(player.getUniqueId()));
            player.sendMessage(core.getMessage("economy.balance-self").replace("%balance%", balance));
            SoundService.economyBalance(player, core);
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (target.getName() == null && !target.hasPlayedBefore()) {
            sender.sendMessage(core.getMessage("economy.player-not-found"));

            if (sender instanceof Player player) {
                SoundService.guiError(player, core);
            }

            return true;
        }

        String balance = economyService.format(economyService.getBalanceCents(target.getUniqueId()));

        sender.sendMessage(core.getMessage("economy.balance-other")
                .replace("%player%", DisplayNames.prefixedDisplayName(target))
                .replace("%balance%", balance));

        if (sender instanceof Player player) {
            SoundService.economyBalance(player, core);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length != 1) {
            return completions;
        }

        String partial = args[0].toLowerCase(Locale.ROOT);

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(online.getName());
            }
        }

        return completions;
    }
}