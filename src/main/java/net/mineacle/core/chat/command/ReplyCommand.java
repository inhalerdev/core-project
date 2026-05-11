package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class ReplyCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final ChatService chatService;

    public ReplyCommand(Core core, ChatService chatService) {
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

        if (args.length < 1) {
            player.sendMessage(core.getMessage("chat.reply-usage"));
            return true;
        }

        chatService.reply(player, String.join(" ", args));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}