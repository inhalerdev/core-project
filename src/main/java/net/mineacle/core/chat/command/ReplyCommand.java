package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.common.sound.SoundService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class ReplyCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final ChatService chatService;

    public ReplyCommand(
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

        if (!player.hasPermission("mineaclechat.message")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length < 1) {
            error(
                    player,
                    core.getMessage("chat.reply-usage")
            );
            return true;
        }

        ChatService.MessageResult result =
                chatService.reply(
                        player,
                        String.join(" ", args)
                );

        switch (result) {
            case SUCCESS -> {
            }
            case NO_REPLY_TARGET -> error(
                    player,
                    core.getMessage("chat.no-reply-target")
            );
            case TARGET_OFFLINE -> error(
                    player,
                    core.getMessage("chat.player-not-found")
            );
            case CANNOT_MESSAGE_SELF -> error(
                    player,
                    core.getMessage("chat.cannot-message-self")
            );
            case TARGET_IGNORING -> error(
                    player,
                    core.getMessage("chat.target-ignoring-you")
            );
            case EMPTY_MESSAGE -> error(
                    player,
                    core.getMessage("chat.reply-usage")
            );
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
        /*
         * /r takes message text, not a player argument. Returning player names
         * here would insert an incorrect recipient into the message body.
         */
        return List.of();
    }

    private void error(Player player, String message) {
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }
}
