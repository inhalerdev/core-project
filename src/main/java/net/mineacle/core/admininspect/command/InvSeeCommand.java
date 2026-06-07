package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class InvSeeCommand implements CommandExecutor, TabCompleter {

    private final Core core;

    public InvSeeCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!viewer.hasPermission("mineacleadmin.invsee")) {
            viewer.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(viewer, core);
            return true;
        }

        if (args.length < 1) {
            viewer.sendMessage(TextColor.color("&cUsage: /invsee <player>"));
            SoundService.guiError(viewer, core);
            return true;
        }

        Player target = DisplayNames.resolveOnline(args[0]);

        if (target == null || !target.isOnline()) {
            viewer.sendMessage(TextColor.color("&cThat player is not online"));
            SoundService.guiError(viewer, core);
            return true;
        }

        if (target.equals(viewer) && !viewer.hasPermission("mineacleadmin.invsee.self")) {
            viewer.sendMessage(TextColor.color("&cYou cannot inspect yourself"));
            SoundService.guiError(viewer, core);
            return true;
        }

        if (target.getGameMode() == GameMode.SPECTATOR) {
            viewer.sendMessage(TextColor.color("&cThat player's inventory cannot be inspected right now"));
            SoundService.guiError(viewer, core);
            return true;
        }

        viewer.openInventory(target.getInventory());

        core.getLogger().info(viewer.getName() + " opened " + target.getName() + "'s inventory with /invsee");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("mineacleadmin.invsee")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> names = new ArrayList<>();

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (DisplayNames.startsWithDisplay(online, args[0])) {
                    names.add(DisplayNames.commandDisplayName(online));
                }
            }

            names.sort(String.CASE_INSENSITIVE_ORDER);
            return PlayerTabComplete.options(args[0], names);
        }

        return List.of();
    }
}
