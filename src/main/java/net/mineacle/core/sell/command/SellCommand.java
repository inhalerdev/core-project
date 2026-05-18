package net.mineacle.core.sell.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.SellHistoryGui;
import net.mineacle.core.sell.gui.SellMultiGui;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SellCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final SellService sellService;

    public SellCommand(Core core, SellService sellService) {
        this.core = core;
        this.sellService = sellService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineaclesell.use")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return true;
        }

        if (commandName.equals("worth")) {
            SoundService.guiClick(player, core);
            WorthGui.open(core, player, sellService, 0);
            return true;
        }

        if (commandName.equals("sellmulti")) {
            SoundService.guiClick(player, core);
            SellMultiGui.open(core, player, sellService);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("history")) {
            SoundService.guiClick(player, core);
            SellHistoryGui.open(core, player, sellService, 0);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("worth")) {
            SoundService.guiClick(player, core);
            WorthGui.open(core, player, sellService, 0);
            return true;
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("multi") || args[0].equalsIgnoreCase("multipliers"))) {
            SoundService.guiClick(player, core);
            SellMultiGui.open(core, player, sellService);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
            sendHeldWorth(player);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("mineaclesell.admin")) {
                player.sendMessage(core.getMessage("general.no-permission"));
                SoundService.guiError(player, core);
                return true;
            }

            sellService.reload();
            player.sendMessage(TextColor.color("&#bbbbbbSell system reloaded"));
            SoundService.guiConfirm(player, core);
            return true;
        }

        SellGui.open(core, player);
        return true;
    }

    private void sendHeldWorth(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item == null || item.getType() == Material.AIR) {
            player.sendMessage(TextColor.color("&cHold an item to check its worth"));
            SoundService.guiError(player, core);
            return;
        }

        if (!sellService.canSell(item)) {
            player.sendMessage(TextColor.color("&cThis item cannot be sold"));
            SoundService.guiError(player, core);
            return;
        }

        long cents = sellService.unitWorthCents(player, item.getType());
        String category = sellService.category(item.getType());
        double multiplier = sellService.multiplier(player.getUniqueId(), category);

        player.sendMessage(TextColor.color(
                "&#bbbbbbWorth: &a" + sellService.format(cents)
                        + " &#bbbbbbper &#ff6fff" + sellService.pretty(item.getType())
                        + " &#bbbbbb(&#ff6fff" + SellService.formatMultiplier(multiplier) + "x&#bbbbbb)"
        ));

        SoundService.economyBalance(player, core);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length != 1 || !command.getName().equalsIgnoreCase("sell")) {
            return completions;
        }

        String partial = args[0].toLowerCase(Locale.ROOT);

        for (String option : List.of("history", "worth", "multi", "hand", "reload")) {
            if (option.startsWith(partial)) {
                completions.add(option);
            }
        }

        return completions;
    }
}
