package net.mineacle.core.economy.command;

import net.mineacle.core.Core;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

public final class EcoCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final EconomyService economyService;

    public EcoCommand(Core core, EconomyService economyService) {
        this.core = core;
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mineacleeconomy.admin")) {
            sender.sendMessage(core.getMessage("general.no-permission"));
            errorSound(sender);
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(core.getMessage("economy.eco-usage"));
            errorSound(sender);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        String target = args[1];

        if (action.equals("reset")) {
            return reset(sender, target);
        }

        if (args.length < 3) {
            sender.sendMessage(core.getMessage("economy.eco-usage"));
            errorSound(sender);
            return true;
        }

        long cents = economyService.parseAmountToCents(args[2]);

        if (cents < 1L) {
            sender.sendMessage(core.getMessage("economy.invalid-amount"));
            errorSound(sender);
            return true;
        }

        return switch (action) {
            case "give" -> apply(sender, target, cents, EcoAction.GIVE);
            case "take" -> apply(sender, target, cents, EcoAction.TAKE);
            case "set" -> apply(sender, target, cents, EcoAction.SET);
            default -> {
                sender.sendMessage(core.getMessage("economy.eco-usage"));
                errorSound(sender);
                yield true;
            }
        };
    }

    private boolean reset(CommandSender sender, String target) {
        List<OfflinePlayer> targets = resolveTargets(sender, target);

        if (targets.isEmpty()) {
            sender.sendMessage(core.getMessage("economy.player-not-found"));
            errorSound(sender);
            return true;
        }

        for (OfflinePlayer offlinePlayer : targets) {
            economyService.reset(offlinePlayer.getUniqueId());
        }

        sender.sendMessage(core.getMessage("economy.eco-reset")
                .replace("%target%", target)
                .replace("%count%", String.valueOf(targets.size())));

        confirmSound(sender);
        return true;
    }

    private boolean apply(CommandSender sender, String target, long cents, EcoAction action) {
        List<OfflinePlayer> targets = resolveTargets(sender, target);

        if (targets.isEmpty()) {
            sender.sendMessage(core.getMessage("economy.player-not-found"));
            errorSound(sender);
            return true;
        }

        Set<UUID> receiveSoundRecipients = new HashSet<>();

        for (OfflinePlayer offlinePlayer : targets) {
            UUID uuid = offlinePlayer.getUniqueId();

            switch (action) {
                case GIVE -> {
                    economyService.give(uuid, cents);
                    Player online = offlinePlayer.getPlayer();

                    if (online != null && online.isOnline()) {
                        economyService.sendOnlinePaidMessage(online, cents);
                        SoundService.economyReceive(online, core);
                        receiveSoundRecipients.add(online.getUniqueId());
                    } else {
                        economyService.addOfflinePayment(uuid, cents, "Console");
                    }
                }
                case TAKE -> economyService.take(uuid, cents);
                case SET -> economyService.setBalance(uuid, cents);
            }
        }

        sender.sendMessage(core.getMessage("economy.eco-success")
                .replace("%action%", action.name().toLowerCase(Locale.ROOT))
                .replace("%target%", target)
                .replace("%count%", String.valueOf(targets.size()))
                .replace("%amount%", economyService.format(cents)));

        confirmSound(sender, receiveSoundRecipients);
        return true;
    }

    private List<OfflinePlayer> resolveTargets(CommandSender sender, String target) {
        List<OfflinePlayer> targets = new ArrayList<>();

        if (target.equals("*")) {
            targets.addAll(Bukkit.getOnlinePlayers());
            return targets;
        }

        if (target.equals("**")) {
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                if (offlinePlayer.getName() != null || offlinePlayer.hasPlayedBefore()) {
                    targets.add(offlinePlayer);
                }
            }

            return targets;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(target);

        if (offlinePlayer.getName() == null && !offlinePlayer.hasPlayedBefore()) {
            return targets;
        }

        targets.add(offlinePlayer);
        return targets;
    }

    private void confirmSound(CommandSender sender) {
        confirmSound(sender, Set.of());
    }

    private void confirmSound(CommandSender sender, Set<UUID> playersWhoAlreadyHeardSound) {
        if (!(sender instanceof Player player)) {
            return;
        }

        if (playersWhoAlreadyHeardSound.contains(player.getUniqueId())) {
            return;
        }

        SoundService.guiConfirm(player, core);
    }

    private void errorSound(CommandSender sender) {
        if (sender instanceof Player player) {
            SoundService.guiError(player, core);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return PlayerTabComplete.options(args[0], List.of("give", "take", "set", "reset"));
        }

        if (args.length == 2) {
            List<String> completions = new ArrayList<>(List.of("*", "**"));

            if (sender instanceof Player player) {
                completions.addAll(PlayerTabComplete.onlinePlayers(player, args[1], true));
            } else {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    completions.add(online.getName());
                }
            }

            return completions;
        }

        if (args.length == 3 && !args[0].equalsIgnoreCase("reset")) {
            return PlayerTabComplete.options(args[2], List.of("1", "10", "100", "1000", "10k", "100k", "1M"));
        }

        return List.of();
    }

    private enum EcoAction {
        GIVE,
        TAKE,
        SET
    }
}
