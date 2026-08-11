package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.NicknameService;
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
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class RealNameCommand
        implements CommandExecutor, TabCompleter {

    private static final String BODY =
            "&#bbbbbb";
    private static final String SECONDARY =
            "&#B078FF";

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
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!sender.hasPermission(
                "mineaclechat.realname"
        )) {
            sender.sendMessage(
                    core.getMessage(
                            "general.no-permission"
                    )
            );
            errorSound(sender);
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(
                    core.getMessage(
                            "chat.realname-usage"
                    )
            );
            errorSound(sender);
            return true;
        }

        OfflinePlayer target =
                nicknameService
                        .findByNickname(
                                args[0]
                        );

        if (target == null) {
            sender.sendMessage(
                    core.getMessage(
                            "chat.realname-not-found"
                    )
            );
            errorSound(sender);
            return true;
        }

        sender.sendMessage(
                TextColor.color(
                        SECONDARY
                                + DisplayNames
                                .displayName(target)
                                + BODY
                                + " belongs to "
                                + SECONDARY
                                + DisplayNames
                                .username(target)
                )
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        if (!sender.hasPermission(
                "mineaclechat.realname"
        ) || args.length != 1) {
            return List.of();
        }

        return PlayerTabComplete.options(
                args[0],
                nicknameService
                        .nicknameSuggestions()
        );
    }

    private void errorSound(
            CommandSender sender
    ) {
        if (sender
                instanceof Player player) {
            SoundService.guiError(
                    player,
                    core
            );
        }
    }
}
