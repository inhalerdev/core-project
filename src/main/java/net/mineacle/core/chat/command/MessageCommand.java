package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class MessageCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final ChatService chatService;

    public MessageCommand(
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

        if (args.length < 2) {
            error(
                    player,
                    core.getMessage("chat.message-usage")
            );
            return true;
        }

        Player target = DisplayNames.resolveOnline(args[0]);

        if (target == null) {
            error(
                    player,
                    core.getMessage("chat.player-not-found")
            );
            return true;
        }

        handleResult(
                player,
                chatService.sendPrivate(
                        player,
                        target,
                        String.join(
                                " ",
                                java.util.Arrays.copyOfRange(
                                        args,
                                        1,
                                        args.length
                                )
                        )
                )
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission("mineaclechat.message")
                || args.length != 1) {
            return List.of();
        }

        return PlayerTabComplete.onlinePlayers(
                player,
                args[0]
        );
    }

    private void handleResult(
            Player player,
            ChatService.MessageResult result
    ) {
        switch (result) {
            case SUCCESS -> {
            }
            case CANNOT_MESSAGE_SELF -> error(
                    player,
                    core.getMessage("chat.cannot-message-self")
            );
            case TARGET_IGNORING -> error(
                    player,
                    core.getMessage("chat.target-ignoring-you")
            );
            case TARGET_OFFLINE -> error(
                    player,
                    core.getMessage("chat.player-not-found")
            );
            case EMPTY_MESSAGE, NO_REPLY_TARGET -> error(
                    player,
                    core.getMessage("chat.message-usage")
            );
        }
    }

    private void error(Player player, String message) {
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }
}
