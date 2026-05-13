package net.mineacle.core.orders.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

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

        if (args[0].equalsIgnoreCase("create")) {
            if (args.length < 3) {
                player.sendMessage(TextColor.color("&d/order create <amount> <price_each>"));
                SoundService.guiError(player, core);
                return true;
            }

            int amount;

            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                player.sendMessage(TextColor.color("&cInvalid amount"));
                SoundService.guiError(player, core);
                return true;
            }

            service.create(player, amount, args[2]);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && player.hasPermission("mineacleorders.admin")) {
            player.sendMessage(TextColor.color("&aOrders reloaded"));
            SoundService.guiConfirm(player, core);
            return true;
        }

        OrdersMainGui.open(player, service);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create");
        }

        return List.of();
    }
}
