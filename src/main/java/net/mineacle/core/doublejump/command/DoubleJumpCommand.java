package net.mineacle.core.doublejump.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.doublejump.listener.DoubleJumpListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class DoubleJumpCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final DoubleJumpListener listener;

    public DoubleJumpCommand(
            Core core,
            DoubleJumpListener listener
    ) {
        this.core = core;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission(
                "mineacledoublejump.admin"
        )) {
            sender.sendMessage(
                    core.getMessage("general.no-permission")
            );
            errorSound(sender);
            return true;
        }

        if (args.length == 1
                && args[0].equalsIgnoreCase("reload")) {
            core.reloadConfig();
            listener.reloadSettingsAndRefresh();

            sender.sendMessage(TextColor.color(
                    "&#bbbbbbDouble Jump reloaded"
            ));

            if (sender instanceof Player player) {
                SoundService.guiConfirm(player, core);
            }

            return true;
        }

        sender.sendMessage(TextColor.color(
                "&cUsage: /doublejump reload"
        ));
        errorSound(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!sender.hasPermission(
                "mineacledoublejump.admin"
        ) || args.length != 1) {
            return List.of();
        }

        return PlayerTabComplete.optionsFiltered(
                args[0],
                List.of("reload")
        );
    }

    private void errorSound(CommandSender sender) {
        if (sender instanceof Player player) {
            SoundService.guiError(player, core);
        }
    }
}
