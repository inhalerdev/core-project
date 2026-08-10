package net.mineacle.core.tpa.command;

import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.tpa.gui.TpaTargetMenuGui;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TpaMenuCommand implements CommandExecutor, TabCompleter {

    private final TpaService tpaService;

    public TpaMenuCommand(TpaService tpaService) {
        this.tpaService = tpaService;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args
    ) {
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

        tpaService.selectMenuTarget(
                player.getUniqueId(),
                target.getUniqueId()
        );
        TpaTargetMenuGui.open(player, target);
        return true;
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args
    ) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)
                || !player.hasPermission("mineacletpa.use")
                || args.length != 1) {
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
