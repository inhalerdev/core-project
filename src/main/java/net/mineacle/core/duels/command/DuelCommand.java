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

        if (sub.equals("reload")) {
            if (!player.hasPermission("mineacleduels.admin")) {
                player.sendMessage(core.getMessage("general.no-permission"));
                return true;
            }

            duelService.reload();
            player.sendMessage(TextColor.color("&#bbbbbbDuels reloaded"));
            return true;
        }

        if (sub.equals("zone")) {
            handleZone(player, label, args);
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

    private void handleZone(Player player, String label, String[] args) {
        if (!player.hasPermission("mineacleduels.admin")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            return;
        }

        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            List<String> zones = duelService.queueZoneIds();

            if (zones.isEmpty()) {
                player.sendMessage(TextColor.color("&#bbbbbbNo duel zones are set"));
                return;
            }

            player.sendMessage(TextColor.color("&dMineacle &8» &#bbbbbbDuel zones"));

            for (String zone : zones) {
                String info = duelService.queueZoneInfo(zone);

                if (info != null) {
                    player.sendMessage(TextColor.color("&#ff88ff" + info));
                }
            }

            return;
        }

        if (args[1].equalsIgnoreCase("set")) {
            String id = args.length >= 3 ? args[2] : player.getWorld().getName();
            double radius = 9.0D;

            if (args.length >= 4) {
                try {
                    radius = Double.parseDouble(args[3]);
                } catch (NumberFormatException ignored) {
                    player.sendMessage(TextColor.color("&cRadius must be a number"));
                    return;
                }
            }

            duelService.setQueueZone(id, player, radius);
            player.sendMessage(TextColor.color("&#bbbbbbDuel queue zone &d" + id + " &#bbbbbbset"));
            player.sendMessage(TextColor.color("&#bbbbbbRadius: &d" + radius));
            return;
        }

        if (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("delete")) {
            if (args.length < 3) {
                player.sendMessage(TextColor.color("&cUsage: /" + label + " zone remove <id>"));
                return;
            }

            if (!duelService.removeQueueZone(args[2])) {
                player.sendMessage(TextColor.color("&cThat duel zone does not exist"));
                return;
            }

            player.sendMessage(TextColor.color("&#bbbbbbDuel queue zone &d" + args[2] + " &#bbbbbbremoved"));
            return;
        }

        player.sendMessage(TextColor.color("&cUsage: /" + label + " zone <set|remove|list>"));
    }

    private void sendUsage(Player player, String label) {
        player.sendMessage(TextColor.color("&dMineacle &8» &#bbbbbbUse &d/" + label + " <player>"));
        player.sendMessage(TextColor.color("&#bbbbbbOr stand in the duel queue circle with another player"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("mineacleduels.use")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>();

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    options.add(online.getName());
                }
            }

            if (player.hasPermission("mineacleduels.admin")) {
                options.add("zone");
                options.add("reload");
            }

            return PlayerTabComplete.options(args[0], options);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("zone") && player.hasPermission("mineacleduels.admin")) {
            return PlayerTabComplete.options(args[1], List.of("set", "remove", "list"));
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("zone")
                && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("delete"))
                && player.hasPermission("mineacleduels.admin")) {
            return PlayerTabComplete.options(args[2], duelService.queueZoneIds());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("set") && player.hasPermission("mineacleduels.admin")) {
            return PlayerTabComplete.options(args[2], List.of(player.getWorld().getName(), "spawn1", "spawn2", "spawn3"));
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("set") && player.hasPermission("mineacleduels.admin")) {
            return PlayerTabComplete.options(args[3], List.of("5", "7", "9", "10"));
        }

        return List.of();
    }
}
