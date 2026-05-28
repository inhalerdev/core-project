package net.mineacle.core.orders.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.orders.gui.OrderCreateGui;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OrderCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final OrderService service;

    public OrderCommand(Core core, OrderService service) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineacleorders.use")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return true;
        }

        if (args.length == 0) {
            OrdersMainGui.open(player, service);
            return true;
        }

        if (args[0].equalsIgnoreCase("search")) {
            if (args.length < 2) {
                OrdersMainGui.setSearch(player, "clear");
            } else {
                OrdersMainGui.setSearch(player, args[1]);
            }

            OrdersMainGui.open(player, service);
            return true;
        }

        if (args[0].equalsIgnoreCase("clear")) {
            OrdersMainGui.setSearch(player, "clear");
            OrdersMainGui.open(player, service);
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            if (args.length == 1) {
                OrderCreateGui.open(player, service);
                return true;
            }

            if (args.length < 4) {
                player.sendMessage(TextColor.color("&#bbbbbbUse: &#ff88ff/order create <item> <amount> <price>"));
                player.sendMessage(TextColor.color("&#bbbbbbExample: &#ff88ff/order create oak_log 64 100k"));
                SoundService.guiError(player, core);
                return true;
            }

            Material material = material(args[1]);

            if (material == null || !material.isItem()) {
                player.sendMessage(TextColor.color("&cUnknown item"));
                SoundService.guiError(player, core);
                return true;
            }

            int amount;

            try {
                amount = Integer.parseInt(args[2].replace(",", "").replace("_", ""));
            } catch (NumberFormatException exception) {
                player.sendMessage(TextColor.color("&cInvalid amount"));
                SoundService.guiError(player, core);
                return true;
            }

            service.create(player, material, amount, args[3]);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && player.hasPermission("mineacleorders.admin")) {
            core.reloadConfig();
            player.sendMessage(TextColor.color("&#bbbbbbOrders reloaded"));
            SoundService.guiConfirm(player, core);
            return true;
        }

        OrdersMainGui.open(player, service);
        return true;
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
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("clear", "create", "search"));

            if (sender.hasPermission("mineacleorders.admin")) {
                options.add("reload");
            }

            return PlayerTabComplete.options(args[0], options);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            String partial = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
            List<String> completions = new ArrayList<>();

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

        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return PlayerTabComplete.options(args[2], List.of("64", "128", "256", "512", "2304"));
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            return PlayerTabComplete.options(args[3], List.of("10k", "100k", "1M", "10M"));
        }

        return List.of();
    }
}
