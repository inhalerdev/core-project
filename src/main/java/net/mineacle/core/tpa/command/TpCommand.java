package net.mineacle.core.tpa.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class TpCommand implements CommandExecutor, TabCompleter {

    private final Core core;

    public TpCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineacletpa.admin")) {
            player.sendMessage(TextColor.color("&cYou do not have permission"));
            SoundService.guiError(player, core);
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(TextColor.color("&cUsage: /tp <player> [target]"));
            SoundService.guiError(player, core);
            return true;
        }

        if (args.length == 1) {
            Player target = DisplayNames.resolveOnline(args[0]);

            if (target == null) {
                player.sendMessage(TextColor.color("&cThat player is offline"));
                SoundService.guiError(player, core);
                return true;
            }

            player.teleport(target.getLocation());
            player.sendMessage(TextColor.color("&#bbbbbbTeleported to &d" + DisplayNames.displayName(target)));
            SoundService.teleportComplete(player, core);
            return true;
        }

        Player from = DisplayNames.resolveOnline(args[0]);
        Player to = DisplayNames.resolveOnline(args[1]);

        if (from == null || to == null) {
            player.sendMessage(TextColor.color("&cThat player is offline"));
            SoundService.guiError(player, core);
            return true;
        }

        from.teleport(to.getLocation());
        player.sendMessage(TextColor.color("&#bbbbbbTeleported &d" + DisplayNames.displayName(from) + " &#bbbbbbto &d" + DisplayNames.displayName(to)));
        SoundService.teleportComplete(player, core);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return new ArrayList<>();
        }

        if (!player.hasPermission("mineacletpa.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            return PlayerTabComplete.onlinePlayers(player, args[0], true);
        }

        if (args.length == 2) {
            return PlayerTabComplete.onlinePlayers(player, args[1], true);
        }

        return new ArrayList<>();
    }
}
