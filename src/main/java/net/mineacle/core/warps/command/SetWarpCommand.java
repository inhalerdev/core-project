package net.mineacle.core.warps.command;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.service.WarpService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public final class SetWarpCommand implements CommandExecutor, TabCompleter {

    private final WarpService warpService;

    public SetWarpCommand(WarpService warpService) {
        this.warpService = warpService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineaclewarps.admin")) {
            player.sendMessage(warpService.noPermissionMessage());
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(TextColor.color("&cUsage: /setwarp <name> [slot] [display name]"));
            return true;
        }

        int slot = 13;

        if (args.length >= 2) {
            try {
                slot = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                slot = 13;
            }
        }

        String displayName = args[0];

        if (args.length >= 3) {
            displayName = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        }

        try {
            warpService.setWarp(args[0], player, slot, displayName);
            player.sendMessage(warpService.setMessage(args[0]));
        } catch (IOException exception) {
            player.sendMessage(TextColor.color("&cCould not save warp"));
            exception.printStackTrace();
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return warpService.warps().stream().map(point -> point.key()).filter(key -> key.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }

        return List.of();
    }
}
