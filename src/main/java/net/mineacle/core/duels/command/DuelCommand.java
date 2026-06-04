package net.mineacle.core.duels.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.duels.service.DuelService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DuelCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final DuelService duelService;

    public DuelCommand(Core core, DuelService duelService) {
        this.core = core;
        this.duelService = duelService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineacleduels.use")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendUsage(player, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("accept")) {
            duelService.accept(player);
            return true;
        }

        if (sub.equals("deny")) {
            duelService.deny(player);
            return true;
        }

        if (sub.equals("cancel")) {
            duelService.cancel(player);
            return true;
        }

        if (sub.equals("leave")) {
            duelService.removeFromQueue(player);
            player.sendMessage(TextColor.color("&#bbbbbbYou left the duel queue"));
            return true;
        }

        if (sub.equals("reload")) {
            if (!player.hasPermission("mineacleduels.admin")) {
                player.sendMessage(core.getMessage("general.no-permission"));
                return true;
            }

            duelService.reload();
            player.sendMessage(TextColor.color("&#bbbbbbDuels reloaded"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);

        if (target == null) {
            player.sendMessage(TextColor.color("&cThat player is not online"));
            return true;
        }

        duelService.challenge(player, target);
        return true;
    }

    private void sendUsage(Player player, String label) {
        player.sendMessage(TextColor.color("&dMineacle &8» &#bbbbbbUse &d/" + label + " <player>"));
        player.sendMessage(TextColor.color("&#bbbbbbOr stand in the duel circle with another player"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("mineacleduels.use")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("accept", "deny", "cancel", "leave"));

            if (player.hasPermission("mineacleduels.admin")) {
                options.add("reload");
            }

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    options.add(online.getName());
                }
            }

            return PlayerTabComplete.options(args[0], options);
        }

        return List.of();
    }
}
