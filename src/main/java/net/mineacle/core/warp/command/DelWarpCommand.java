package net.mineacle.core.warps.command;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.service.WarpService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.IOException;
import java.util.List;

public final class DelWarpCommand implements CommandExecutor, TabCompleter {

    private final WarpService warpService;

    public DelWarpCommand(WarpService warpService) {
        this.warpService = warpService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mineaclewarps.admin")) {
            sender.sendMessage(warpService.noPermissionMessage());
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(TextColor.color("&cUsage: /delwarp <name>"));
            return true;
        }

        try {
            if (!warpService.deleteWarp(args[0])) {
                sender.sendMessage(warpService.notFoundMessage(args[0]));
                return true;
            }

            sender.sendMessage(warpService.deletedMessage(args[0]));
        } catch (IOException exception) {
            sender.sendMessage(TextColor.color("&cCould not delete warp"));
            exception.printStackTrace();
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return warpService.warpKeys(args[0]);
        }

        return List.of();
    }
}
