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
import java.util.Locale;

public final class DelWarpCommand implements CommandExecutor, TabCompleter {

    private final WarpService warpService;

    public DelWarpCommand(WarpService warpService) {
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
            sendError(player, "&cUsage: /delwarp <name>");
            return true;
        }

        if (!warpService.removeWarp(args[0])) {
            sendError(player, "&cThat warp does not exist");
            return true;
        }

        send(player, "&#bbbbbbWarp &d" + args[0] + " &#bbbbbbdeleted");
        SoundService.guiConfirm(player, warpService.core());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player) || !player.hasPermission("mineaclewarps.admin")) {
            return completions;
        }

        if (args.length != 1) {
            return completions;
        }

        String partial = args[0].toLowerCase(Locale.ROOT);

        for (String id : warpService.warpIds()) {
            if (id.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(id);
            }
        }

        return completions;
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
