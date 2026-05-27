package net.mineacle.core.warps.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.gui.WarpGui;
import net.mineacle.core.warps.model.WarpPoint;
import net.mineacle.core.warps.service.WarpService;
import net.mineacle.core.warps.service.WarpTeleportService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class WarpCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final WarpService warpService;
    private final WarpTeleportService teleportService;

    public WarpCommand(Core core, WarpService warpService, WarpTeleportService teleportService) {
        this.core = core;
        this.warpService = warpService;
        this.teleportService = teleportService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineaclewarps.use")) {
            player.sendMessage(warpService.noPermissionMessage());
            return true;
        }

        if (args.length == 0) {
            WarpGui.open(core, player, warpService);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && player.hasPermission("mineaclewarps.admin")) {
            warpService.reload();
            player.sendMessage(TextColor.color("&#bbbbbbWarps reloaded"));
            return true;
        }

        WarpPoint point = warpService.warp(args[0]);

        if (point == null) {
            player.sendMessage(warpService.notFoundMessage(args[0]));
            return true;
        }

        teleportService.teleport(player, point);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        List<String> completions = new ArrayList<>(warpService.warpKeys(args[0]));

        if (sender.hasPermission("mineaclewarps.admin") && "reload".startsWith(args[0].toLowerCase())) {
            completions.add("reload");
        }

        return completions;
    }
}
