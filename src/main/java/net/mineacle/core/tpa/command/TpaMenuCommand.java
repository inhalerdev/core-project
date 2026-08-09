package net.mineacle.core.tpa.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.tpa.gui.TpaTargetMenuGui;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TpaMenuCommand implements CommandExecutor, TabCompleter {

    public static final String META_TARGET = "mineacle_tpa_menu_target";

    private final Core core;

    public TpaMenuCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(TextColor.color("&cOnly players can use this command"));
            return true;
        }

        if (!player.hasPermission("mineacletpa.use")) {
            send(player, "&cYou do not have permission");
            return true;
        }

        if (args.length < 1) {
            send(player, "&cUsage: /tpamenu <player>");
            return true;
        }

        Player target = DisplayNames.resolveOnline(args[0]);

        if (target == null) {
            target = Bukkit.getPlayerExact(args[0]);
        }

        if (target == null) {
            send(player, "&cThat player is not online");
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(player, "&cYou cannot teleport to yourself");
            return true;
        }

        player.setMetadata(META_TARGET, new FixedMetadataValue(core, target.getUniqueId().toString()));
        TpaTargetMenuGui.open(player, target);
        return true;
    }

    private void send(Player player, String message) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)) {
            return completions;
        }

        if (!player.hasPermission("mineacletpa.use")) {
            return completions;
        }

        if (args.length != 1) {
            return completions;
        }

        String partial = args[0].toLowerCase(Locale.ROOT);

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }

            String commandName = DisplayNames.commandDisplayName(online);

            if (commandName.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(commandName);
                continue;
            }

            if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(online.getName());
            }
        }

        return completions;
    }
}