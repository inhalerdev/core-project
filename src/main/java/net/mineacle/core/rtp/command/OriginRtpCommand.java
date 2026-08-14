package net.mineacle.core.rtp.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.rtp.gui.RtpMenuGui;
import net.mineacle.core.rtp.service.OriginRtpQueueService;
import net.mineacle.core.rtp.service.OriginRtpSearchSettings;
import net.mineacle.core.rtp.service.RtpMenuService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class OriginRtpCommand
        implements CommandExecutor, TabCompleter {

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String BODY = "&#bbbbbb";

    private static final int MAX_PLAYER_SUGGESTIONS = 100;

    private static final List<String> DESTINATIONS =
            List.of(
                    "overworld",
                    "nether",
                    "end"
            );

    private final Core core;
    private final OriginRtpQueueService queueService;
    private final RtpMenuService menuService;

    public OriginRtpCommand(
            Core core,
            OriginRtpQueueService queueService,
            RtpMenuService menuService
    ) {
        this.core = core;
        this.queueService = queueService;
        this.menuService = menuService;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        boolean canonicalAdminRoot =
                label.equalsIgnoreCase("originrtp");

        if (!(sender instanceof Player player)) {
            handleConsole(sender, args);
            return true;
        }

        if (!player.hasPermission("mineaclertp.use")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (canonicalAdminRoot
                && player.hasPermission("mineaclertp.admin")
                && args.length == 1
                && args[0].equalsIgnoreCase("reload")) {
            reload(player);
            return true;
        }

        if (canonicalAdminRoot
                && player.hasPermission("mineaclertp.admin")
                && args.length >= 1
                && unknownDestination(args[0])) {
            handleTarget(player, args);
            return true;
        }

        if (args.length == 0) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> RtpMenuGui.open(
                            player,
                            menuService
                    )
            );
            return true;
        }

        if (args.length != 1
                || unknownDestination(args[0])) {
            usage(player);
            return true;
        }

        queueService.request(
                player,
                canonicalDestination(args[0])
        );
        return true;
    }

    private void handleConsole(
            CommandSender sender,
            String[] args
    ) {
        if (args.length == 1
                && args[0].equalsIgnoreCase("reload")) {
            core.reloadConfig();
            queueService.reload();
            menuService.reload();
            sender.sendMessage(
                    TextColor.color(
                            BODY + "RTP reloaded"
                    )
            );
            return;
        }

        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(
                    TextColor.color(
                            BODY
                                    + "Usage: "
                                    + PRIMARY
                                    + "/originrtp <player> "
                                    + "[overworld|nether|end]"
                    )
            );
            return;
        }

        Player target = DisplayNames.resolveOnline(
                args[0]
        );

        if (target == null) {
            sender.sendMessage(
                    TextColor.color(
                            "&cThat player is not online"
                    )
            );
            return;
        }

        String destination = args.length == 2
                ? canonicalDestination(args[1])
                : "overworld";

        if (unknownDestination(destination)) {
            sender.sendMessage(
                    TextColor.color(
                            "&cUnknown RTP destination"
                    )
            );
            return;
        }

        if (!queueService.request(target, destination)) {
            sender.sendMessage(
                    TextColor.color(
                            "&cCould not queue random teleport for "
                                    + DisplayNames.displayName(target)
                    )
            );
            return;
        }

        sender.sendMessage(
                TextColor.color(
                        BODY
                                + "Queued "
                                + SECONDARY
                                + DisplayNames.displayName(target)
                                + BODY
                                + " for "
                                + PRIMARY
                                + displayName(destination)
                                + BODY
                                + " RTP"
                )
        );
    }

    private void handleTarget(
            Player sender,
            String[] args
    ) {
        if (args.length > 2) {
            adminUsage(sender);
            return;
        }

        Player target = DisplayNames.resolveOnline(
                args[0]
        );

        if (target == null) {
            error(
                    sender,
                    "&cThat player is not online"
            );
            return;
        }

        String destination = args.length == 2
                ? canonicalDestination(args[1])
                : "overworld";

        if (unknownDestination(destination)) {
            error(
                    sender,
                    "&cUnknown RTP destination"
            );
            return;
        }

        if (!queueService.request(target, destination)) {
            error(
                    sender,
                    "&cCould not queue random teleport for "
                            + DisplayNames.displayName(target)
            );
            return;
        }

        sender.sendMessage(
                TextColor.color(
                        BODY
                                + "Queued "
                                + SECONDARY
                                + DisplayNames.displayName(target)
                                + BODY
                                + " for "
                                + PRIMARY
                                + displayName(destination)
                                + BODY
                                + " RTP"
                )
        );
        SoundService.guiConfirm(sender, core);
    }

    private void reload(Player player) {
        core.reloadConfig();
        queueService.reload();
        menuService.reload();

        player.sendMessage(
                TextColor.color(
                        BODY + "RTP reloaded"
                )
        );
        SoundService.guiConfirm(player, core);
    }

    private void usage(Player player) {
        player.sendMessage(
                TextColor.color(
                        BODY
                                + "Usage: "
                                + PRIMARY
                                + "/rtp [overworld|nether|end]"
                )
        );
        SoundService.guiError(player, core);
    }

    private void adminUsage(Player player) {
        player.sendMessage(
                TextColor.color(
                        BODY
                                + "Usage: "
                                + PRIMARY
                                + "/originrtp <player> "
                                + "[overworld|nether|end]"
                )
        );
        SoundService.guiError(player, core);
    }

    private void error(
            Player player,
            String message
    ) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }

    private boolean unknownDestination(String input) {
        return !DESTINATIONS.contains(
                canonicalDestination(input)
        );
    }

    private String canonicalDestination(String input) {
        return OriginRtpSearchSettings
                .canonicalDestination(input);
    }

    private String displayName(String destination) {
        String configured = core.getConfig().getString(
                "origin-rtp.destinations."
                        + destination
                        + ".display-name"
        );

        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return switch (destination) {
            case "nether" -> "Nether";
            case "end" -> "The End";
            default -> "Overworld";
        };
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        boolean canonicalAdminRoot =
                alias.equalsIgnoreCase("originrtp");
        boolean admin = !(sender instanceof Player)
                || sender.hasPermission("mineaclertp.admin");

        if (args.length == 1) {
            String partial = normalize(args[0]);

            /*
             * /rtp and /wild intentionally expose destinations only.
             * Player names never leak from player-facing roots.
             */
            if (!canonicalAdminRoot || !admin) {
                return matching(
                        DESTINATIONS,
                        partial
                );
            }

            Set<String> options =
                    new LinkedHashSet<>(DESTINATIONS);
            options.add("reload");

            if (sender instanceof Player player) {
                PlayerTabComplete.onlinePlayers(
                                player,
                                args[0],
                                true
                        )
                        .stream()
                        .limit(MAX_PLAYER_SUGGESTIONS)
                        .forEach(options::add);
            } else {
                Bukkit.getOnlinePlayers()
                        .stream()
                        .map(DisplayNames::commandDisplayName)
                        .filter(
                                value -> normalize(value)
                                        .startsWith(partial)
                        )
                        .limit(MAX_PLAYER_SUGGESTIONS)
                        .forEach(options::add);
            }

            return matching(
                    List.copyOf(options),
                    partial
            );
        }

        if (args.length == 2
                && canonicalAdminRoot
                && admin
                && !args[0].equalsIgnoreCase("reload")) {
            return matching(
                    DESTINATIONS,
                    normalize(args[1])
            );
        }

        return List.of();
    }

    private List<String> matching(
            List<String> options,
            String partial
    ) {
        List<String> matches = new ArrayList<>();

        for (String option : options) {
            if (normalize(option).startsWith(partial)) {
                matches.add(option);
            }
        }

        matches.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(matches);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT);
    }
}
