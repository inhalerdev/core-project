package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BountyCommand implements CommandExecutor, TabCompleter {

    public static final String META_TARGET = "mineacle_bounty_target";
    public static final String META_AMOUNT = "mineacle_bounty_amount";

    private final Core core;
    private final BountyService bountyService;

    public BountyCommand(Core core, BountyService bountyService) {
        this.core = core;
        this.bountyService = bountyService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineaclebounty.use")) {
            sendError(player, "§cYou do not have permission");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            BountyMainGui.open(core, player, bountyService, 0);
            return true;
        }

        if (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("place")) {
            if (args.length < 3) {
                sendError(player, "§cUsage: /bounty add <player> <amount>");
                return true;
            }

            return confirm(player, args[1], args[2]);
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("mineaclebounty.admin")) {
                sendError(player, "§cYou do not have permission");
                return true;
            }

            player.sendMessage(TextColor.color("&#bbbbbbBounty system reloaded"));
            SoundService.guiConfirm(player, core);
            return true;
        }

        if (args[0].equalsIgnoreCase("remove")) {
            return remove(player, args);
        }

        if (args.length < 2) {
            sendError(player, "§cUsage: /bounty add <player> <amount>");
            return true;
        }

        return confirm(player, args[0], args[1]);
    }

    private boolean confirm(Player player, String targetInput, String amountInput) {
        OfflinePlayer target = bountyService.resolveTarget(targetInput);

        if (target == null) {
            sendError(player, "§cThat player could not be found");
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            sendError(player, "§cYou cannot place a bounty on yourself");
            return true;
        }

        long amount = bountyService.parseAmount(amountInput);

        if (amount <= 0L) {
            sendError(player, "§cThat is not a valid amount");
            return true;
        }

        long minimum = bountyService.minimumCents();
        long maximum = bountyService.maximumCents();

        if (amount < minimum) {
            sendError(player, "§cMinimum bounty is " + bountyService.format(minimum));
            return true;
        }

        if (maximum > 0L && amount > maximum) {
            sendError(player, "§cMaximum bounty is " + bountyService.format(maximum));
            return true;
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null || !economy.has(player.getUniqueId(), amount)) {
            sendError(player, "§cYou do not have enough money");
            return true;
        }

        player.setMetadata(META_TARGET, new FixedMetadataValue(core, target.getUniqueId().toString()));
        player.setMetadata(META_AMOUNT, new FixedMetadataValue(core, amount));

        BountyConfirmGui.open(core, player, target, amount, bountyService);
        return true;
    }

    private boolean remove(Player player, String[] args) {
        if (!player.hasPermission("mineaclebounty.admin")) {
            sendError(player, "§cYou do not have permission");
            return true;
        }

        if (args.length < 2) {
            sendError(player, "§cUsage: /bounty remove <player>");
            return true;
        }

        OfflinePlayer target = bountyService.resolveTarget(args[1]);

        if (target == null) {
            sendError(player, "§cThat player could not be found");
            return true;
        }

        long removed = bountyService.remove(target.getUniqueId());

        if (removed <= 0L) {
            sendError(player, "§cThat player has no bounty");
            return true;
        }

        player.sendMessage(TextColor.color("&#bbbbbbRemoved bounty from &#ff88ff" + DisplayNames.displayName(target)));
        SoundService.guiConfirm(player, core);
        return true;
    }

    private void sendError(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message)));
        SoundService.guiError(player, core);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            for (String option : List.of("add", "list", "remove", "reload")) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (DisplayNames.startsWithDisplay(player, args[0])) {
                    completions.add(DisplayNames.commandDisplayName(player));
                }
            }

            return completions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("set")
                || args[0].equalsIgnoreCase("place")
                || args[0].equalsIgnoreCase("remove"))) {
            String partial = args[1];

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (DisplayNames.startsWithDisplay(player, partial)) {
                    completions.add(DisplayNames.commandDisplayName(player));
                }
            }
        }

        if (args.length == 3 && (args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("set")
                || args[0].equalsIgnoreCase("place"))) {
            String partial = args[2].toLowerCase(Locale.ROOT);

            for (String option : List.of("1k", "10k", "100k", "1m")) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }
        }

        if (args.length == 2 && !args[0].equalsIgnoreCase("remove")
                && !args[0].equalsIgnoreCase("add")
                && !args[0].equalsIgnoreCase("set")
                && !args[0].equalsIgnoreCase("place")) {
            for (String option : List.of("1k", "10k", "100k", "1m")) {
                if (option.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    completions.add(option);
                }
            }
        }

        return completions;
    }
}
