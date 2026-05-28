package net.mineacle.core.warps.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
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
import java.util.Locale;

public final class WarpCommand implements CommandExecutor, TabCompleter {

    private final WarpService warpService;
    private final WarpTeleportService teleportService;

    public WarpCommand(WarpService warpService, WarpTeleportService teleportService) {
        this.warpService = warpService;
        this.teleportService = teleportService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("mineaclewarps.admin")) {
                sendError(player, warpService.noPermissionMessage());
                return true;
            }

            warpService.reload();
            send(player, "&#bbbbbbWarps reloaded");
            SoundService.guiConfirm(player, net.mineacle.core.Core.instance());
            return true;
        }

        WarpPoint point = warpService.warp(args[0]);

        if (point == null) {
            sendError(player, warpService.notFoundMessage(args[0]));
            return true;
        }

        teleportService.teleport(player, point);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)) {
            return completions;
        }

        if (args.length != 1) {
            return completions;
        }

        String partial = args[0].toLowerCase(Locale.ROOT);

        if (player.hasPermission("mineaclewarps.admin") && "reload".startsWith(partial)) {
            completions.add("reload");
        }

        completions.addAll(warpService.warpKeys(partial));
        return completions;
    }

    private void sendUsage(Player player) {
        List<String> keys = warpService.warpKeys("");
        String available = keys.isEmpty() ? "none" : String.join(", ", keys);

        send(player, "&#bbbbbbUsage: &d/warp <location>");
        send(player, "&#bbbbbbAvailable warps: &d" + available);
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private void sendError(Player player, String message) {
        send(player, message);
        SoundService.guiError(player, net.mineacle.core.Core.instance());
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
