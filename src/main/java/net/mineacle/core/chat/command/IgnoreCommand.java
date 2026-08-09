package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class IgnoreCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final ChatService chatService;

    public IgnoreCommand(
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

        if (args.length != 1) {
            error(
                    player,
                    core.getMessage("chat.ignore-usage")
            );
            return true;
        }

        OfflinePlayer target = DisplayNames.resolveOffline(args[0]);

        if (target == null
                || (target.getName() == null
                && !target.hasPlayedBefore())) {
            error(
                    player,
                    core.getMessage("chat.player-not-found")
            );
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            error(
                    player,
                    core.getMessage("chat.cannot-ignore-self")
            );
            return true;
        }

        ChatService.IgnoreResult result =
                chatService.toggleIgnoreDetailed(player, target);
        String targetName = DisplayNames.displayName(target);

        switch (result) {
            case NOW_IGNORING -> {
                player.sendMessage(TextColor.color(
                        "&#bbbbbbYou are now ignoring &#bbbbbb"
                                + targetName
                ));
                SoundService.featureEnable(player, core);
            }
            case NO_LONGER_IGNORING -> {
                player.sendMessage(TextColor.color(
                        "&#bbbbbbYou are no longer ignoring &#bbbbbb"
                                + targetName
                ));
                SoundService.featureDisable(player, core);
            }
            case STORAGE_ERROR -> error(
                    player,
                    TextColor.color(
                            "&cCould not update your ignore list"
                    )
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
        if (!(sender instanceof Player player)
                || !player.hasPermission("mineaclechat.ignore")
                || args.length != 1) {
            return List.of();
        }

        return PlayerTabComplete.onlinePlayers(
                player,
                args[0]
        );
    }

    private void error(Player player, String message) {
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }
}
