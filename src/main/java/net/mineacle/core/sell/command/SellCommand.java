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
            WorthGui.open(core, player, sellService, 0);
            return true;
        }

        if (commandName.equals("sellmulti")) {
            SellMultiGui.open(core, player, sellService);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("history")) {
            SellHistoryGui.open(core, player, sellService, 0);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("worth")) {
            WorthGui.open(core, player, sellService, 0);
            return true;
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("multi") || args[0].equalsIgnoreCase("multipliers"))) {
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

        if (args.length > 0 && args[0].equalsIgnoreCase("demand")) {
            handleDemandCommand(player, args);
            return true;
        }

        SellGui.open(core, player);
        return true;
    }

    private void handleDemandCommand(Player player, String[] args) {
        if (!player.hasPermission("mineaclesell.admin")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return;
        }

        if (args.length < 2) {
            player.sendMessage(TextColor.color("&cUsage: /sell demand <recalc|reset|item>"));
            SoundService.guiError(player, core);
            return;
        }

        if (args[1].equalsIgnoreCase("recalc")) {
            sellService.recalculateDemand();
            player.sendMessage(TextColor.color("&#bbbbbbSell demand recalculated"));
            SoundService.guiConfirm(player, core);
            return;
        }

        if (args[1].equalsIgnoreCase("reset")) {
            sellService.resetDemandData();
            player.sendMessage(TextColor.color("&#bbbbbbSell demand data reset"));
            SoundService.guiConfirm(player, core);
            return;
        }

        Material material = material(args[1]);

        if (material == null || !material.isItem()) {
            player.sendMessage(TextColor.color("&cUnknown item"));
            SoundService.guiError(player, core);
            return;
        }

        long base = sellService.baseWorthCents(material);
        double demand = sellService.demandMultiplier(material);
        long adjusted = sellService.unitWorthCents(player, material);

        player.sendMessage(TextColor.color("&#bbbbbbItem: &#ff88ff" + sellService.pretty(material)));
        player.sendMessage(TextColor.color("&#bbbbbbBase: &a" + sellService.format(base)));
        player.sendMessage(TextColor.color("&#bbbbbbDemand: &#ff88ff" + SellService.formatMultiplier(demand) + "x"));
        player.sendMessage(TextColor.color("&#bbbbbbAdjusted: &a" + sellService.format(adjusted)));
        SoundService.economyBalance(player, core);
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

        long cents = sellService.stackWorthCents(player, item);
        String category = sellService.category(item.getType());
        double multiplier = sellService.multiplier(player.getUniqueId(), category);
        double demand = sellService.demandMultiplier(item.getType());

        player.sendMessage(TextColor.color(
                "&#bbbbbbWorth: &a" + sellService.format(cents)
                        + " &#bbbbbbper &#ff88ff" + sellService.pretty(item.getType())
                        + " &#bbbbbb(&#ff88ff" + SellService.formatMultiplier(multiplier) + "x&#bbbbbb)"
        ));

        if (Math.abs(demand - 1.0D) >= 0.01D) {
            player.sendMessage(TextColor.color("&#bbbbbbDemand: &#ff88ff" + SellService.formatMultiplier(demand) + "x"));
        }

        SoundService.economyBalance(player, core);
    }

    private Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!command.getName().equalsIgnoreCase("sell")) {
            return completions;
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            for (String option : List.of("history", "worth", "multi", "hand", "reload", "demand")) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }

            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("demand") && sender.hasPermission("mineaclesell.admin")) {
            String partial = args[1].toLowerCase(Locale.ROOT);

            for (String option : List.of("recalc", "reset")) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }

            for (Material material : Material.values()) {
                if (!material.isItem()) {
                    continue;
                }

                String name = material.name().toLowerCase(Locale.ROOT);

                if (name.startsWith(partial)) {
                    completions.add(name);
                }
            }
        }

        return completions;
    }
}
