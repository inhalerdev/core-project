package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class IgnoreListCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final ChatService chatService;

    public IgnoreListCommand(Core core, ChatService chatService) {
        this.core = core;
        this.chatService = chatService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        List<String> ignored = chatService.ignoreList(player);

        if (ignored.isEmpty()) {
            player.sendMessage(core.getMessage("chat.ignore-list-empty"));
            return true;
        }

        player.sendMessage(core.getMessage("chat.ignore-list-header"));

        for (String name : ignored) {
            player.sendMessage(core.getMessage("chat.ignore-list-line").replace("%player%", name));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}