package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.common.player.DisplayNames;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class MessageCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final ChatService chatService;

    public MessageCommand(Core core, ChatService chatService) {
        this.core = core;
        this.chatService = chatService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineaclechat.message")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(core.getMessage("chat.message-usage"));
            return true;
        }

        Player target = DisplayNames.resolveOnline(args[0]);

        if (target == null) {
            player.sendMessage(core.getMessage("chat.player-not-found"));
            return true;
        }

        chatService.sendPrivate(player, target, join(args, 1));
        return true;
    }

    private String join(String[] args, int start) {
        StringBuilder builder = new StringBuilder();

        for (int index = start; index < args.length; index++) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(args[index]);
        }

        return builder.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length != 1) {
            return completions;
        }

        String partial = args[0];

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (DisplayNames.startsWithDisplay(online, partial)) {
                completions.add(DisplayNames.commandDisplayName(online));
            }
        }

        return completions;
    }
}
