package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class RealNameCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final NicknameService nicknameService;

    public RealNameCommand(
            Core core,
            NicknameService nicknameService
    ) {
        this.core = core;
        this.nicknameService = nicknameService;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("mineaclechat.realname")) {
            sender.sendMessage(
                    core.getMessage("general.no-permission")
            );
            errorSound(sender);
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(
                    core.getMessage("chat.realname-usage")
            );
            errorSound(sender);
            return true;
        }

        OfflinePlayer target =
                nicknameService.findByNickname(args[0]);

        if (target == null) {
            sender.sendMessage(
                    core.getMessage("chat.realname-not-found")
            );
            errorSound(sender);
            return true;
        }

        sender.sendMessage(TextColor.color(
                "&#bbbbbb"
                        + nicknameService.displayName(target)
                        + " &#bbbbbbbelongs to &#bbbbbb"
                        + nicknameService.username(target)
        ));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!sender.hasPermission("mineaclechat.realname")
                || args.length != 1) {
            return List.of();
        }

        return PlayerTabComplete.options(
                args[0],
                nicknameService.nicknameSuggestions()
        );
    }

    private void errorSound(CommandSender sender) {
        if (sender instanceof Player player) {
            SoundService.guiError(player, core);
        }
    }
}
