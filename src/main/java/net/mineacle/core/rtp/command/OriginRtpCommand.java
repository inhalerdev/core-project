package net.mineacle.core.rtp.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.rtp.gui.RtpMenuGui;
import net.mineacle.core.rtp.service.OriginRtpQueueService;
import net.mineacle.core.rtp.service.RtpMenuService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OriginRtpCommand implements CommandExecutor, TabCompleter {

    private static final List<String> DESTINATIONS = List.of("origins", "nether", "end");

    private final Core core;
    private final OriginRtpQueueService queueService;
    private final RtpMenuService menuService;

    public OriginRtpCommand(Core core, OriginRtpQueueService queueService, RtpMenuService menuService) {
        this.core = core;
        this.queueService = queueService;
        this.menuService = menuService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length < 1) {
                sender.sendMessage("Usage: /originrtp <player> [origins|nether|end]");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);

            if (target == null) {
                sender.sendMessage(TextColor.color("&cThat player is not online"));
                return true;
            }

            String destination = args.length >= 2 ? normalizeDestination(args[1]) : "origins";

            if (destination == null) {
                sender.sendMessage(TextColor.color("&cUsage: /originrtp <player> [origins|nether|end]"));
                return true;
            }

            queueService.request(target, destination);
            return true;
        }

        if (!player.hasPermission("mineaclertp.use")) {
            send(player, "§cYou do not have permission");
            return true;
        }

        if (args.length == 0) {
            MenuHistory.openRoot(core, player, () -> RtpMenuGui.open(player, menuService, RtpMenuGui.ORIGINS));
            return true;
        }

        String destination = normalizeDestination(args[0]);

        if (destination != null) {
            queueService.request(player, destination);
            return true;
        }

        if (!player.hasPermission("mineaclertp.admin")) {
            send(player, "§cYou do not have permission");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);

        if (target == null) {
            player.sendMessage(TextColor.color("&cThat player is not online"));
            return true;
        }

        destination = args.length >= 2 ? normalizeDestination(args[1]) : "origins";

        if (destination == null) {
            player.sendMessage(TextColor.color("&cUsage: /originrtp <player> [origins|nether|end]"));
            return true;
        }

        queueService.request(target, destination);
        return true;
    }

    private String normalizeDestination(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String value = input.toLowerCase(Locale.ROOT);

        if (value.equals("origin") || value.equals("overworld") || value.equals("world")) {
            return "origins";
        }

        if (value.equals("the_nether")) {
            return "nether";
        }

        if (value.equals("the_end")) {
            return "end";
        }

        if (DESTINATIONS.contains(value)) {
            return value;
        }

        return null;
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            if (sender instanceof Player player && player.hasPermission("mineaclertp.use")) {
                for (String destination : DESTINATIONS) {
                    if (destination.startsWith(partial)) {
                        completions.add(destination);
                    }
                }
            }

            if (!(sender instanceof Player) || sender.hasPermission("mineaclertp.admin")) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                        completions.add(online.getName());
                    }
                }
            }

            return completions;
        }

        if (args.length == 2 && (!(sender instanceof Player) || sender.hasPermission("mineaclertp.admin"))) {
            String partial = args[1].toLowerCase(Locale.ROOT);

            for (String destination : DESTINATIONS) {
                if (destination.startsWith(partial)) {
                    completions.add(destination);
                }
            }
        }

        return completions;
    }
}
