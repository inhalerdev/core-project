package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class IgnoreListCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final ChatService chatService;

    public IgnoreListCommand(
            Core core,
            ChatService chatService
    ) {
        this.core = core;
        this.chatService = chatService;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (!player.hasPermission("mineaclechat.ignore")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length != 0) {
            error(
                    player,
                    TextColor.color("&cUsage: /ignorelist")
            );
            return true;
        }

        List<String> ignoredPlayers =
                chatService.ignoreList(player);

        if (ignoredPlayers.isEmpty()) {
            player.sendMessage(
                    core.getMessage("chat.ignore-list-empty")
            );
            return true;
        }

        player.sendMessage(TextColor.color("&dIgnored Players"));

        for (String name : ignoredPlayers) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbb- &#bbbbbb" + name
            ));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        return List.of();
    }

    private void error(Player player, String message) {
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }
}
