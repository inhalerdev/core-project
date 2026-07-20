package net.mineacle.core.nametag;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class NametagCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final NametagService service;

    public NametagCommand(
            Core core,
            NametagService service
    ) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission(
                "mineaclenametags.admin"
        )) {
            error(
                    sender,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length != 1
                || !args[0].equalsIgnoreCase("reload")) {
            error(
                    sender,
                    "&#bbbbbbUsage: &d/mineaclenametags reload"
            );
            return true;
        }

        service.reload();
        service.refreshAll();

        sender.sendMessage(TextColor.color(
                "&#bbbbbbNametags reloaded"
        ));

        if (sender instanceof Player player) {
            SoundService.guiConfirm(player, core);
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
        if (!sender.hasPermission(
                "mineaclenametags.admin"
        )
                || args.length != 1) {
            return List.of();
        }

        String partial = args[0]
                .toLowerCase(Locale.ROOT);

        return "reload".startsWith(partial)
                ? List.of("reload")
                : List.of();
    }

    private void error(
            CommandSender sender,
            String message
    ) {
        sender.sendMessage(TextColor.color(message));

        if (sender instanceof Player player) {
            SoundService.guiError(player, core);
        }
    }
}
