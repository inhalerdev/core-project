package net.mineacle.core.links;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class LinksCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final LinksService service;

    public LinksCommand(Core core, LinksService service) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineaclelinks.use")) {
            player.sendMessage(TextColor.color(service.noPermission()));
            SoundService.guiError(player, core);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("mineaclelinks.admin")) {
                player.sendMessage(TextColor.color(service.noPermission()));
                SoundService.guiError(player, core);
                return true;
            }

            service.reload();
            player.sendMessage(TextColor.color("&#bbbbbbLinks system reloaded"));
            SoundService.guiConfirm(player, core);
            return true;
        }

        LinksGui.open(player, service);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("mineaclelinks.admin") && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }

        return Collections.emptyList();
    }
}
