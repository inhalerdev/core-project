package net.mineacle.core.punish.command;

import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.punish.service.PunishService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BanMenuCommand implements CommandExecutor, TabCompleter {
    private final PunishService service;

    public BanMenuCommand(PunishService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage("Players only");
            return true;
        }
        if (!admin.hasPermission("mineaclepunish.admin")) {
            admin.sendMessage(TextColor.color("&cYou do not have permission"));
            SoundService.guiError(admin, service.core());
            return true;
        }
        if (args.length < 1) {
            admin.sendMessage(TextColor.color("&cUsage: /" + label + " <player>"));
            SoundService.guiError(admin, service.core());
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        service.open(admin, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("mineaclepunish.admin")) {
            return completions;
        }
        if (args.length != 1) {
            return completions;
        }

        String partial = args[0].toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(player.getName());
            }
        }
        return completions;
    }
}
