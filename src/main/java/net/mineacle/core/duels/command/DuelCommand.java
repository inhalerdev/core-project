package net.mineacle.core.duels.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.duels.service.DuelService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DuelCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final DuelService duelService;
    private final Runnable reloadAction;

    public DuelCommand(
            Core core,
            DuelService duelService,
            Runnable reloadAction
    ) {
        this.core = core;
        this.duelService = duelService;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (!player.hasPermission("mineacleduels.use")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length == 0) {
            error(player, "&cUsage: /duel <player>");
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "accept" -> {
                requireNoExtraArguments(player, args, () ->
                        duelService.accept(player)
                );
                return true;
            }
            case "deny" -> {
                requireNoExtraArguments(player, args, () ->
                        duelService.deny(player)
                );
                return true;
            }
            case "cancel" -> {
                requireNoExtraArguments(player, args, () ->
                        duelService.cancel(player)
                );
                return true;
            }
            case "reload" -> {
                reload(player, args);
                return true;
            }
            case "zone" -> {
                handleZone(player, args);
                return true;
            }
            default -> {
            }
        }

        if (args.length != 1) {
            error(player, "&cUsage: /duel <player>");
            return true;
        }

        Player target = DisplayNames.resolveOnline(args[0]);

        if (target == null) {
            error(player, "&cThat player is not online");
            return true;
        }

        duelService.challenge(player, target);
        return true;
    }

    private void reload(Player player, String[] args) {
        if (!player.hasPermission("mineacleduels.admin")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return;
        }

        if (args.length != 1) {
            error(player, "&cUsage: /duel reload");
            return;
        }

        reloadAction.run();
        player.sendMessage(TextColor.color(
                "&#bbbbbbDuels reloaded"
        ));
        SoundService.guiConfirm(player, core);
    }

    private void handleZone(
            Player player,
            String[] args
    ) {
        if (!player.hasPermission("mineacleduels.admin")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return;
        }

        if (args.length == 1
                || (args.length == 2
                && args[1].equalsIgnoreCase("list"))) {
            List<String> zones = duelService.queueZoneIds();

            if (zones.isEmpty()) {
                player.sendMessage(TextColor.color(
                        "&#bbbbbbNo duel zones are set"
                ));
                return;
            }

            player.sendMessage(TextColor.color("&dDuel Zones"));

            for (String zone : zones) {
                String info = duelService.queueZoneInfo(zone);

                if (info != null) {
                    player.sendMessage(TextColor.color(
                            "&#bbbbbb" + info
                    ));
                }
            }

            return;
        }

        String operation = args[1].toLowerCase(Locale.ROOT);

        if (operation.equals("set")) {
            if (args.length > 4) {
                error(
                        player,
                        "&cUsage: /duel zone set <id> [radius]"
                );
                return;
            }

            String id = args.length >= 3
                    ? args[2]
                    : player.getWorld().getName();
            double radius = 9.0D;

            if (args.length == 4) {
                try {
                    radius = Double.parseDouble(args[3]);
                } catch (NumberFormatException exception) {
                    error(player, "&cRadius must be a number");
                    return;
                }
            }

            if (!Double.isFinite(radius)) {
                error(player, "&cRadius must be a finite number");
                return;
            }

            double appliedRadius = Math.max(
                    1.0D,
                    Math.min(128.0D, radius)
            );
            String normalized = duelService.setQueueZone(
                    id,
                    player,
                    appliedRadius
            );

            player.sendMessage(TextColor.color(
                    "&#bbbbbbDuel queue zone &d"
                            + normalized
                            + " &#bbbbbbset"
            ));
            player.sendMessage(TextColor.color(
                    "&#bbbbbbRadius: &#ff88ff"
                            + duelService.trimNumber(appliedRadius)
            ));
            SoundService.guiConfirm(player, core);
            return;
        }

        if (operation.equals("remove")
                || operation.equals("delete")) {
            if (args.length != 3) {
                error(
                        player,
                        "&cUsage: /duel zone remove <id>"
                );
                return;
            }

            if (!duelService.removeQueueZone(args[2])) {
                error(
                        player,
                        "&cThat duel zone does not exist"
                );
                return;
            }

            player.sendMessage(TextColor.color(
                    "&#bbbbbbDuel queue zone &d"
                            + args[2]
                            + " &#bbbbbbremoved"
            ));
            SoundService.guiConfirm(player, core);
            return;
        }

        error(
                player,
                "&cUsage: /duel zone <set|remove|list>"
        );
    }

    private void requireNoExtraArguments(
            Player player,
            String[] args,
            Runnable action
    ) {
        if (args.length != 1) {
            error(player, "&cUsage: /duel " + args[0]);
            return;
        }

        action.run();
    }

    private void error(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission("mineacleduels.use")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(
                    PlayerTabComplete.onlinePlayers(
                            player,
                            args[0]
                    )
            );
            options.add("accept");
            options.add("deny");
            options.add("cancel");

            if (player.hasPermission("mineacleduels.admin")) {
                options.add("zone");
                options.add("reload");
            }

            return PlayerTabComplete.options(args[0], options);
        }

        if (!player.hasPermission("mineacleduels.admin")) {
            return List.of();
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("zone")) {
            return PlayerTabComplete.options(
                    args[1],
                    List.of("set", "remove", "list")
            );
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("zone")
                && (args[1].equalsIgnoreCase("remove")
                || args[1].equalsIgnoreCase("delete"))) {
            return PlayerTabComplete.options(
                    args[2],
                    duelService.queueZoneIds()
            );
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("zone")
                && args[1].equalsIgnoreCase("set")) {
            return PlayerTabComplete.options(
                    args[2],
                    List.of(
                            player.getWorld().getName(),
                            "spawn1",
                            "spawn2",
                            "spawn3"
                    )
            );
        }

        if (args.length == 4
                && args[0].equalsIgnoreCase("zone")
                && args[1].equalsIgnoreCase("set")) {
            return PlayerTabComplete.options(
                    args[3],
                    List.of("5", "7", "9", "10")
            );
        }

        return List.of();
    }
}
