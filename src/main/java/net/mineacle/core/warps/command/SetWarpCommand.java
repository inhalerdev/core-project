package net.mineacle.core.warps.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warps.service.WarpService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

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
            sendError(player, "&cYou do not have permission");
            return true;
        }

        if (args.length < 1) {
            sendError(player, "&cUsage: /setwarp <name> [slot] [display name]");
            return true;
        }

        String id = args[0];
        Integer slot = null;
        int displayStart = 1;

        if (args.length >= 2) {
            try {
                slot = Integer.parseInt(args[1]);
                displayStart = 2;
            } catch (NumberFormatException ignored) {
                displayStart = 1;
            }
        }

        String displayName = joinArgs(args, displayStart);

        if (!warpService.addWarp(player, id, slot, displayName)) {
            sendError(player, "&cCould not set that warp");
            return true;
        }

        send(player, "&#bbbbbbWarp &d" + id + " &#bbbbbbset");
        SoundService.guiConfirm(player, warpService.core());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<>();
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder builder = new StringBuilder();

        for (int i = start; i < args.length; i++) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }

        return builder.toString();
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private void sendError(Player player, String message) {
        send(player, message);
        SoundService.guiError(player, warpService.core());
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
