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
            if (!player.hasPermission("mineaclewarp.admin")) {
                player.sendMessage(TextColor.color("&cYou do not have permission"));
                return true;
            }

            warpService.reload();
            player.sendMessage(TextColor.color("&#bbbbbbWarp reloaded"));
            return true;
        }

        String warpId = args[0].toLowerCase(Locale.ROOT);
        WarpPoint warp = warpService.warp(warpId);

        if (warp == null) {
            player.sendMessage(TextColor.color("&cUnknown warp"));
            sendAvailable(player);
            return true;
        }

        teleportService.teleport(player, warp);
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(TextColor.color("&#bbbbbbUsage: &d/warp <location>"));
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
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        if (args.length != 1) {
            return List.of();
        }

        String partial = args[0].toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();

        for (String key : warpService.warpKeys("")) {
            if (key.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(key);
            }
        }

        if (player.hasPermission("mineaclewarp.admin") && "reload".startsWith(partial)) {
            completions.add("reload");
        }

        return completions;
    }
}
