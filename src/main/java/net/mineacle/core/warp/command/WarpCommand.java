package net.mineacle.core.warp.command;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warp.model.WarpPoint;
import net.mineacle.core.warp.service.WarpService;
import net.mineacle.core.warp.service.WarpTeleportService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class WarpCommand implements CommandExecutor, TabCompleter {

    private final WarpService warpService;
    private final WarpTeleportService teleportService;

    public WarpCommand(WarpService warpService, WarpTeleportService teleportService) {
        this.warpService = warpService;
        this.teleportService = teleportService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineaclewarps.use")) {
            player.sendMessage(warpService.noPermissionMessage());
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        String warpId = args[0].toLowerCase(Locale.ROOT);
        WarpPoint warp = warpService.warp(warpId);

        if (warp == null) {
            player.sendMessage(warpService.notFoundMessage(args[0]));
            sendAvailable(player);
            return true;
        }

        teleportService.teleport(player, warp);
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(TextColor.color("&#bbbbbbUsage: &d/warp <warp>"));
        sendAvailable(player);
    }

    private void sendAvailable(Player player) {
        List<String> keys = warpService.warpKeys("");

        if (keys.isEmpty()) {
            player.sendMessage(TextColor.color("&cNo warps are available"));
            return;
        }

        player.sendMessage(TextColor.color("&#bbbbbbWarps: &d" + String.join("&#bbbbbb, &d", keys)));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("mineaclewarps.use")) {
            return List.of();
        }

        if (args.length == 1) {
            return warpService.warpKeys(args[0]);
        }

        return List.of();
    }
}
