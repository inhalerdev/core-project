package net.mineacle.core.sell.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.PlayerTabComplete;
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
            if (args.length > 0) {
                sendNamedWorth(player, args);
                return true;
            }

            WorthGui.open(core, player, sellService, 0);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("history")) {
            SellHistoryGui.open(core, player, sellService, 0);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("worth")) {
            if (args.length > 1) {
                sendNamedWorth(player, dropFirst(args));
                return true;
            }

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
            WorthGui.clearCatalogCache();
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

    private void sendNamedWorth(Player player, String[] args) {
        Material material = material(String.join("_", args));

        if (material == null || !material.isItem()) {
            player.sendMessage(TextColor.color("&cUnknown item"));
            SoundService.guiError(player, core);
            return;
        }

        boolean dragonEgg = material == Material.DRAGON_EGG;
        long unit = dragonEgg ? Math.max(sellService.baseWorthCents(material), 1000000L) : sellService.unitWorthCents(player, material);

        if (unit <= 0L && !dragonEgg) {
            player.sendMessage(TextColor.color("&cThat item has no worth"));
            SoundService.guiError(player, core);
            return;
        }

        int stackSize = Math.max(1, material.getMaxStackSize());
        long stackPrice = dragonEgg ? unit : unit * stackSize;

        player.sendMessage(TextColor.color("&#bbbbbbItem: &#ff88ff" + sellService.pretty(material)));
        player.sendMessage(TextColor.color("&#bbbbbbWorth: &a" + sellService.format(unit)));

        if (!dragonEgg) {
            player.sendMessage(TextColor.color("&#bbbbbbStack Price: &a" + sellService.format(stackPrice)));
        } else {
            player.sendMessage(TextColor.color("&cNot sellable"));
            player.sendMessage(TextColor.color("&#bbbbbbUnique server trophy item"));
        }

        SoundService.economyBalance(player, core);
    }

    private void handleDemandCommand(Player player, String[] args) {
        if (!player.hasPermission("mineaclesell.admin")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return;
        }

        if (args.length < 2) {
            player.sendMessage(TextColor.color("&cUsage: /sell demand <item|recalc|reset>"));
            SoundService.guiError(player, core);
            return;
        }

        if (args[1].equalsIgnoreCase("recalc") || args[1].equalsIgnoreCase("rotate")) {
            sellService.recalculateDemand();
            WorthGui.clearCatalogCache();
            player.sendMessage(TextColor.color("&#bbbbbbSell demand rotated"));
            SoundService.guiConfirm(player, core);
            return;
        }

        if (args[1].equalsIgnoreCase("reset")) {
            sellService.resetDemandData();
            WorthGui.clearCatalogCache();
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
        String category = sellService.category(material);
        double demand = sellService.demandMultiplier(material);
        long adjusted = sellService.unitWorthCents(player, material);
        long windowAmount = sellService.demandWindowAmount(material);
        long windowCents = sellService.demandWindowTotalCents(material);

        player.sendMessage(TextColor.color("&#bbbbbbItem: &#ff88ff" + sellService.pretty(material)));
        player.sendMessage(TextColor.color("&#bbbbbbCategory: &#ff88ff" + sellService.categoryDisplay(category)));
        player.sendMessage(TextColor.color("&#bbbbbbBase Price: &a" + sellService.format(base)));
        player.sendMessage(TextColor.color("&#bbbbbbServer Demand: &#ff88ff" + SellService.formatMultiplier(demand) + "x"));
        player.sendMessage(TextColor.color("&#bbbbbbFinal Unit Price: &a" + sellService.format(adjusted)));

        if (sellService.isActiveDemandItem(material)) {
            player.sendMessage(TextColor.color("&#bbbbbbDemand Tier: &#ff88ff" + sellService.demandTierDisplay(material)));
            player.sendMessage(TextColor.color("&#bbbbbbSold This Cycle: &#ff88ff" + windowAmount + " &#bbbbbbitems / &a" + sellService.format(windowCents)));
        } else {
            player.sendMessage(TextColor.color("&#bbbbbbDemand Tier: &#bbbbbbNormal"));
        }

        SoundService.economyBalance(player, core);
    }

    private void sendHeldWorth(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item == null || item.getType().isAir()) {
            player.sendMessage(TextColor.color("&cHold an item to check its worth"));
            SoundService.guiError(player, core);
            return;
        }

        if (!sellService.canSell(item)) {
            player.sendMessage(TextColor.color("&cThis item cannot be sold"));
            SoundService.guiError(player, core);
            return;
        }

        long stack = sellService.stackWorthCents(player, item);
        long unit = sellService.unitWorthCents(player, item.getType());
        Material material = item.getType();

        player.sendMessage(TextColor.color("&#bbbbbbItem: &#ff88ff" + item.getAmount() + "x " + sellService.pretty(material)));
        player.sendMessage(TextColor.color("&#bbbbbbWorth: &a" + sellService.format(unit)));
        player.sendMessage(TextColor.color("&#bbbbbbStack Price: &a" + sellService.format(stack)));
        SoundService.economyBalance(player, core);
    }

    private Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("-", "_");

        try {
            return Material.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String[] dropFirst(String[] args) {
        String[] updated = new String[Math.max(0, args.length - 1)];
        if (updated.length > 0) {
            System.arraycopy(args, 1, updated, 0, updated.length);
        }
        return updated;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player player) || !player.hasPermission("mineaclesell.use")) {
            return List.of();
        }

        if (commandName.equals("worth")) {
            return itemCompletions(args);
        }

        if (!commandName.equals("sell")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("hand", "history", "multi", "worth"));

            if (player.hasPermission("mineaclesell.admin")) {
                options.add("demand");
                options.add("reload");
            }

            return PlayerTabComplete.options(args[0], options);
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("worth")) {
            return itemCompletions(dropFirst(args));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("demand") && player.hasPermission("mineaclesell.admin")) {
            List<String> completions = new ArrayList<>(List.of("recalc", "rotate", "reset"));
            String partial = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);

            for (Material material : Material.values()) {
                if (!material.isItem()) {
                    continue;
                }

                String name = material.name().toLowerCase(Locale.ROOT);

                if (partial.isEmpty() || name.startsWith(partial)) {
                    completions.add(name);
                }
            }

            return completions;
        }

        return List.of();
    }

    private List<String> itemCompletions(String[] args) {
        String partial = String.join("_", args).toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();

        for (Material material : Material.values()) {
            if (!material.isItem() || material == Material.AIR || material.name().endsWith("_SPAWN_EGG")) {
                continue;
            }

            String name = material.name().toLowerCase(Locale.ROOT);

            if (partial.isBlank() || name.startsWith(partial)) {
                completions.add(name);
            }

            if (completions.size() >= 80) {
                break;
            }
        }

        return completions;
    }
}
