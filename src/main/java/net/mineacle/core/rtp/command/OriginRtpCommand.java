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
        String used = label.toLowerCase(Locale.ROOT);

        if (sender instanceof Player player && (used.equals("rtp") || used.equals("wild"))) {
            if (!player.hasPermission("mineaclertp.use")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            if (args.length == 0) {
                MenuHistory.openRoot(core, player, () -> RtpMenuGui.open(player, menuService, RtpMenuGui.MAIN));
                return true;
            }

            if (args[0].equalsIgnoreCase("origins") || args[0].equalsIgnoreCase("origin")) {
                MenuHistory.openRoot(core, player, () -> RtpMenuGui.open(player, menuService, RtpMenuGui.ORIGINS));
                return true;
            }

            queueService.request(player, args[0]);
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Usage: /originrtp <player> [origins|nether|end]");
                return true;
            }

            if (!player.hasPermission("mineaclertp.use")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            queueService.request(player);
            return true;
        }

        if (sender instanceof Player player && !player.hasPermission("mineaclertp.admin")) {
            send(player, "§cYou do not have permission");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);

        if (target == null) {
            sender.sendMessage(TextColor.color("&cThat player is not online"));
            return true;
        }

        String destination = args.length >= 2 ? args[1] : "origins";
        queueService.request(target, destination);
        return true;
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
        String used = alias.toLowerCase(Locale.ROOT);

        if (sender instanceof Player player && (used.equals("rtp") || used.equals("wild"))) {
            if (!player.hasPermission("mineaclertp.use")) {
                return completions;
            }

            if (args.length == 1) {
                String partial = args[0].toLowerCase(Locale.ROOT);

                for (String option : List.of("origins", "nether", "end")) {
                    if (option.startsWith(partial)) {
                        completions.add(option);
                    }
                }
            }

            return completions;
        }

        if (args.length == 1) {
            if (sender instanceof Player player && !player.hasPermission("mineaclertp.admin")) {
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

        if (args.length == 2) {
            String partial = args[1].toLowerCase(Locale.ROOT);

            for (String option : List.of("origins", "nether", "end")) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }
        }

        return completions;
    }
}
