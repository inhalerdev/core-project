package net.mineacle.core.rtp.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.rtp.service.OriginRtpQueueService;
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

    public OriginRtpCommand(Core core, OriginRtpQueueService queueService) {
        this.core = core;
        this.queueService = queueService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Usage: /originrtp <player>");
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

        queueService.request(target);
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

        if (args.length != 1) {
            return completions;
        }

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
}